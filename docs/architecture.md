# Architecture Notes

## Phase 14 system flow

1. Payment Service authenticates customer requests, applies customer-scoped idempotency, and commits a payment plus `payments.created.v1` outbox row atomically.
2. The outbox relay claims rows with `FOR UPDATE SKIP LOCKED`, commits the claim, then publishes outside the database transaction.
3. Kafka fan-out uses independent consumer groups: Transaction updates business state, Audit preserves immutable evidence, and Notification creates durable delivery work.
4. Transaction Service writes its transaction plus `processed_events` deduplication state atomically; failures receive bounded retry and durable DLT recovery.
5. Audit Service validates the event contract, computes a SHA-256 digest, and inserts one append-only row; malformed input is isolated to the audit DLT.
6. Notification Service inserts one durable delivery request per `(source_event_id, channel)`, then a leased dispatcher performs provider calls outside the claim transaction.
7. All four services expose Micrometer telemetry and optional OpenTelemetry/OTLP traces.
8. Kubernetes runs each service as an independently scalable Deployment behind a ClusterIP Service. Helm owns application compute/configuration.
9. The AWS reference maps application compute to EKS, images to ECR, Kafka to MSK Serverless, Redis to ElastiCache Serverless, and each service-owned PostgreSQL datastore to a separate RDS instance.
10. EKS Pod Identity associates each workload ServiceAccount with a dedicated IAM role; the application does not store static AWS credentials.
11. GitHub Actions can assume a separate deployment role through OIDC, push immutable ECR images, discover managed endpoints, and perform an atomic Helm release when the manual AWS workflow is explicitly triggered.

## Logical runtime architecture

```text
                          optional Ingress
                               |
             +-----------------+------------------+
             |                 |                  |
             v                 v                  v
      Payment Service   Transaction Service   Audit / Notification Services
             |                 |                  |
      Payment Deployment Transaction Deployment  Audit / Notification Deployments
             |                 |                  |
       Payment DB + Redis   Transaction DB     Audit DB / Notification DB
             |                 |                  |
             +-----------------+------------------+
                               |
                              Kafka
                               |
                    independent consumer groups

OAuth2/OIDC JWKS --------------------> all application Deployments
OTLP collector <---------------------- all application Deployments
```

Each service owns its own database and never reads another service's tables. Consumer-group independence means one downstream service can be unavailable without gating another consumer's work.

## AWS production mapping

```text
                           GitHub Actions
                                |
                    OIDC / AssumeRoleWithWebIdentity
                                |
                                v
                        AWS deploy IAM role
                          /             \
                         v               v
                     Amazon ECR      Amazon EKS
                                         |
              +--------------------------+--------------------------+
              |                          |                          |
              v                          v                          v
         Payment pod              Transaction pod          Audit / Notification pods
              |                          |                          |
      Pod Identity role          Pod Identity role          Pod Identity roles
              |                          |                          |
              +--------------------------+--------------------------+
                                         |
                                  Amazon MSK Serverless
                                    IAM + SASL_SSL

Payment pod ------> RDS payments ------+
Transaction pod --> RDS transactions    |  four service-owned
Audit pod --------> RDS audit           |  PostgreSQL boundaries
Notification pod -> RDS notifications --+

Payment pod ------> ElastiCache Serverless Redis
EKS control plane -> CloudWatch logs
```

The VPC spans two Availability Zones. EKS application nodes and managed data services use private subnets. Public subnets are reserved for load-balancer/NAT infrastructure. The reference uses two NAT gateways so one AZ's private workloads do not depend on the other AZ's NAT path.

## Payment correctness boundary

PostgreSQL remains authoritative for customer-scoped idempotency. Redis is an optimization, and the transactional outbox is the durable boundary between payment state and Kafka intent. Outbox publication remains intentionally at least once.

AWS does not change this model. RDS replaces the deployment location of PostgreSQL and ElastiCache replaces the deployment location of Redis, but Redis remains fail-open and PostgreSQL remains authoritative.

## Transaction processing and DLT recovery

Transaction Service consumes `payments.created.v1` with at-least-once semantics. New events create a business transaction and `processed_events` marker in one database transaction; duplicate event IDs become no-ops. Retryable failures receive bounded retry, then durable DLT indexing and secured replay.

MSK Serverless preserves the same Kafka delivery semantics. A broker-level redelivery or an operator replay can still produce another delivery, so database idempotency remains required.

## Audit Service correctness model

Audit Service preserves immutable integration-boundary evidence rather than mutable business state. Logical event ID and Kafka source position are unique, a PostgreSQL trigger rejects `UPDATE` and `DELETE`, and event-detail reads recompute a SHA-256 digest. This is integrity evidence, not cryptographic non-repudiation.

The AWS mapping gives Audit its own RDS instance; it does not collapse the audit datastore into another service's schema.

## Notification Service correctness model

Notification delivery has two separate failure domains: Kafka ingestion and the external provider boundary. Keeping them separate avoids treating a temporary provider outage as a poisoned Kafka record.

```text
payments.created.v1
      |
      v
INSERT notification_deliveries
ON CONFLICT (source_event_id, channel) DO NOTHING
      |
      v
PENDING / RETRY_PENDING / stale PROCESSING
      |
      v
SELECT ... FOR UPDATE SKIP LOCKED
      |
      v
PROCESSING + attempt_count++ + processing_started_at
      |
      v
commit short claim transaction
      |
      v
provider.send(idempotencyKey = notificationId)
   | success
   +--> SENT
   |
   | retryable failure and attempts remain
   +--> RETRY_PENDING + exponential next_attempt_at
   |
   `--> permanent / exhausted --> FAILED
```

The provider call runs outside the database transaction, keeping locks short and allowing multiple instances to claim disjoint batches. Stale `PROCESSING` rows become eligible after the lease timeout. A crash can happen after provider acceptance but before `SENT` is stored, so the same notification ID is reused as the provider idempotency key. End-to-end exactly-once provider delivery is not claimed.

## Kubernetes workload boundary

Helm deploys application compute only. PostgreSQL, Kafka, Redis, OIDC, and tracing infrastructure are runtime dependencies supplied through values.

Each application has a Deployment, ClusterIP Service, rolling update policy, startup/readiness/liveness probes, graceful shutdown, resource requests/limits, `autoscaling/v2` HPA, PodDisruptionBudget, and topology spreading.

Containers run as a non-root UID/GID with dropped capabilities, `allowPrivilegeEscalation: false`, `RuntimeDefault` seccomp, and a read-only root filesystem. A writable `emptyDir` is mounted at `/tmp` for JVM compatibility.

### Dedicated ServiceAccounts

Phase 14 changes the chart from one shared ServiceAccount to one identity per workload:

```text
payment-platform-payment
payment-platform-transaction
payment-platform-audit
payment-platform-notification
```

That distinction is operationally important on AWS because EKS Pod Identity associates those ServiceAccounts with different IAM roles. The services still do not need Kubernetes API access, so ordinary Kubernetes service-account token automounting remains disabled.

## AWS identity model

### Workload identity

Each workload IAM role trusts `pods.eks.amazonaws.com` and is associated through `aws_eks_pod_identity_association`. The EKS Pod Identity Agent is installed as an EKS add-on.

MSK permissions are scoped by service responsibility. Payment needs producer access to `payments.created.v1`. Transaction, Audit, and Notification receive access to their source topic, service-specific DLT topics, and corresponding consumer-group prefixes.

The application runtime includes the AWS MSK IAM authentication plugin and the AWS Helm profile sets:

```text
security.protocol=SASL_SSL
sasl.mechanism=AWS_MSK_IAM
sasl.jaas.config=software.amazon.msk.auth.iam.IAMLoginModule required;
sasl.client.callback.handler.class=software.amazon.msk.auth.iam.IAMClientCallbackHandler
```

Local Docker/Testcontainers runs do not enable those properties.

### Deployment identity

GitHub Actions uses a separate IAM role. Its trust policy requires the GitHub OIDC audience `sts.amazonaws.com` and a subject restricted to this repository's protected `production` Environment.

The deployment role can authenticate to ECR, push to the four application repositories, describe the EKS cluster and managed data endpoints, read only the RDS-managed database secret ARNs, and access the `payments` Kubernetes namespace through an EKS access entry/policy association.

No long-lived AWS access keys are stored in GitHub.

## AWS data and secret boundaries

Terraform provisions four RDS PostgreSQL instances instead of sharing one database server-level schema boundary. Each instance enables storage encryption, backups, private networking, and RDS-managed master credentials. Deletion protection and Multi-AZ are configurable and enabled by the reference production defaults.

RDS stores generated master credentials in Secrets Manager. The manual deployment workflow reads those values with short-lived credentials, masks passwords in Actions logs, and materializes the existing `payment-platform-secrets` Kubernetes Secret contract immediately before Helm deployment.

This is an executable bridge, not the final ideal secret-delivery architecture. A mature environment should use External Secrets or the Secrets Store CSI Driver and separate application users from RDS master credentials.

## ECR and release immutability

There is one ECR repository per Spring Boot service. Terraform configures immutable tags, scan-on-push, encryption, and retention of the newest images.

The AWS deployment workflow tags each image with the selected commit SHA unless an explicit immutable tag is supplied. Helm receives that exact tag, avoiding a `latest`-based production rollout.

## Ingress / ALB boundary

The AWS Helm profile includes AWS Load Balancer Controller-compatible Ingress defaults but keeps Ingress disabled.

Phase 14 does **not** install the AWS Load Balancer Controller and does **not** provision an Internet-facing ALB. Those require environment-specific DNS, certificate, exposure, and WAF decisions. The application chart can enable Ingress after that controller/platform layer exists.

## Observability boundary on AWS

Existing Micrometer/Prometheus/OpenTelemetry instrumentation remains application-owned. EKS control-plane logs are sent to CloudWatch by Terraform.

The AWS Helm profile expects an OTLP endpoint named `adot-collector.monitoring.svc.cluster.local`, but Phase 14 does **not** install ADOT or a managed Prometheus/Grafana stack. This is intentionally documented rather than falsely claiming full cloud observability is already deployed.

## Terraform / infrastructure validation

CI treats the AWS configuration as executable infrastructure:

```text
terraform fmt -check -diff -recursive
terraform init -backend=false
terraform validate
helm lint
helm template (generic profile)
helm template (AWS profile)
Maven verify
four Docker image builds
```

`terraform validate` loads the pinned AWS provider schema, so unsupported resource arguments are caught before merge without requiring AWS credentials or running `terraform apply`.

A real environment needs an S3 Terraform state backend configured at `terraform init` time. State files and local Terraform caches are gitignored.

## AWS deployment workflow

`.github/workflows/aws-deploy.yml` is manual (`workflow_dispatch`) and runs only from `main` using the protected `production` GitHub Environment.

The sequence is:

```text
GitHub OIDC -> assume deploy role
      |
      v
Maven package
      |
      v
Build 4 images -> push immutable tags to ECR
      |
      v
aws eks update-kubeconfig
      |
      +--> discover 4 RDS endpoints + secret ARNs
      +--> discover ElastiCache endpoint
      `--> discover MSK Serverless IAM bootstrap brokers
      |
      v
materialize payment-platform-secrets
      |
      v
helm upgrade --install --atomic
      |
      v
verify 4 deployment rollouts
```

The normal CI workflow never assumes AWS credentials, never calls `terraform apply`, and never runs this deployment job. Merging the Phase 14 code therefore does not incur AWS infrastructure cost by itself.

## Security boundaries

```text
Payment Service
  payments:write -> POST /api/v1/payments
  payments:read  -> GET /api/v1/payments/{id}

Transaction Service
  ops:read  -> inspect DLT recovery state
  ops:write -> replay DLT record

Audit Service
  audit:read -> audit timeline + evidence detail

Notification Service
  notification:read -> delivery detail + payment delivery lookup

All services
  ops:read -> protected metrics / Prometheus

AWS runtime
  dedicated Pod Identity role -> MSK permissions per workload

GitHub production environment
  OIDC deploy role -> ECR + namespace-scoped EKS deployment operations
```

Application OAuth2/JWT authorization and AWS workload IAM solve different problems. Kubernetes/AWS identity is not used as a substitute for end-user API authorization.

## Scaling and failure boundaries

HPA replica growth does not imply unlimited throughput. Effective capacity still depends on MSK partition count, RDS connection pools/IO, Redis capacity, EKS nodes, notification-provider quotas, NAT/network limits, and retry behavior.

AWS-managed infrastructure also does not change exactly-once boundaries. Outbox publication remains at least once; downstream consumers remain idempotent; DLT replay can redeliver records; and notification provider delivery still depends on provider-side idempotency support for crash-after-send recovery.

## Verification

Application Testcontainers suites continue to verify PostgreSQL/Kafka/Redis correctness independently of the cloud packaging. The infrastructure CI additionally verifies:

- Terraform formatting and AWS provider-schema validity;
- generic and AWS-specific Helm rendering;
- four Deployments, Services, HPAs, PDBs, and dedicated ServiceAccounts;
- presence of MSK IAM and Redis TLS runtime settings in the AWS profile;
- successful Maven `verify` across all services;
- successful construction of all four hardened runtime images.

See [`aws.md`](aws.md) for the provisioning/deployment runbook and [`kubernetes.md`](kubernetes.md) for the cloud-neutral Helm model.

## Future expansion

Useful next hardening areas are ADOT/managed metrics and alerting, AWS Load Balancer Controller + ACM/DNS/WAF integration, External Secrets/Secrets Store CSI, load/performance testing, database-user separation/rotation, and expanding Audit ingestion to additional lifecycle event topics. A real email/SMS adapter should remain a swappable edge component with credentials supplied through the secret-management boundary.
