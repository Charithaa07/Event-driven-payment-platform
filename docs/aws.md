# AWS production reference architecture

Phase 14 maps the Kubernetes-ready payment platform onto managed AWS infrastructure without changing the application's correctness semantics. Phase 16 hardens that reference architecture with a private-only EKS control plane, customer-managed KMS envelope encryption for Kubernetes Secrets, restricted managed-service security groups, and a private deployment-runner boundary. Terraform describes the cloud resources; Helm remains the application deployment boundary; and the AWS deployment workflow is deliberately manual so merging code never creates billable infrastructure or changes a live environment automatically.

> The repository contains a validated AWS reference implementation and deployment workflow. It does **not** mean an AWS account has already been provisioned or that these services are currently running in AWS.

## Runtime mapping

```text
GitHub Actions
    |
    | workflow_dispatch + protected environment
    v
Private self-hosted deployment runner (VPC reachable)
    |
    | OIDC -> short-lived AWS role
    +---------------------> Amazon ECR
    |
    | private EKS API
    v
Amazon EKS (private application subnets)
    |
    +-- Payment Service --------------------> RDS PostgreSQL (payments)
    |       |                                  ElastiCache Serverless Redis
    |       +--> MSK Serverless (IAM)
    |
    +-- Transaction Service ----------------> RDS PostgreSQL (transactions)
    |       +--> MSK Serverless (IAM)
    |
    +-- Audit Service ----------------------> RDS PostgreSQL (audit)
    |       +--> MSK Serverless (IAM)
    |
    +-- Notification Service --------------> RDS PostgreSQL (notifications)
            +--> MSK Serverless (IAM)

Each workload ServiceAccount
    -> EKS Pod Identity association
    -> dedicated IAM role
    -> least-privilege MSK permissions
```

The VPC spans two Availability Zones. Application nodes, RDS, MSK, and ElastiCache use private subnets. Two NAT gateways provide AZ-local outbound access for private workloads. Public subnets are reserved for explicitly public infrastructure such as NAT gateways or a future internet-facing load balancer; subnet-level automatic public-IP assignment is disabled.

## What Terraform provisions

`deploy/aws/terraform` contains the production reference infrastructure:

- a two-AZ VPC with public/private subnets, Internet Gateway, two NAT gateways, routing, and security groups;
- an Amazon EKS cluster with a private-only Kubernetes API endpoint and managed node group;
- customer-managed KMS envelope encryption for Kubernetes Secrets with automatic key rotation;
- EKS control-plane logging to CloudWatch;
- the VPC CNI, CoreDNS, kube-proxy, EKS Pod Identity Agent, and Metrics Server add-ons;
- four immutable Amazon ECR repositories with scan-on-push and lifecycle retention;
- four independent Amazon RDS for PostgreSQL instances, one per service-owned datastore;
- RDS-managed master credentials in AWS Secrets Manager;
- Amazon MSK Serverless with IAM/SASL client authentication;
- ElastiCache Serverless for Redis;
- one EKS Pod Identity IAM role per application workload;
- a GitHub Actions deployment IAM role trusted through GitHub OIDC;
- namespace-scoped EKS access for the deployment role.

The application chart intentionally does not deploy PostgreSQL, Kafka, or Redis into EKS. Those stateful dependencies remain managed AWS services.

## Network security boundary

The Kubernetes API endpoint is private-only:

```text
endpoint_private_access = true
endpoint_public_access  = false
```

This deliberately means a stock GitHub-hosted Actions runner cannot administer the cluster. The production deployment job is therefore pinned to a self-hosted runner with the label `payment-platform-deploy` and network reachability to the VPC/private EKS API. Opening the control plane to `0.0.0.0/0` merely to make a hosted runner convenient is not part of this reference architecture.

The RDS, MSK, and Redis security groups accept only their application protocol from the platform VPC and do not define unrestricted outbound rules. Security groups are stateful, so return traffic for established inbound connections is still allowed without a general `0.0.0.0/0` egress rule on those managed-service groups.

Public subnets retain their route to the Internet Gateway for NAT gateways and possible future load-balancer infrastructure, but `map_public_ip_on_launch` is disabled. A resource that truly needs a public address must receive one explicitly.

## EKS secret encryption

The EKS cluster uses a dedicated customer-managed KMS key for Kubernetes Secret envelope encryption. Terraform enables KMS key rotation and configures the EKS `encryption_config` for the `secrets` resource.

This complements, rather than replaces, the existing application secret boundaries. Database passwords continue to originate from RDS-managed Secrets Manager secrets; the Kubernetes Secret is only the runtime handoff expected by the current Helm chart.

## Identity boundaries

### GitHub Actions -> AWS

The deployment workflow uses GitHub's OIDC token to call `sts:AssumeRoleWithWebIdentity`. No long-lived AWS access key or secret key is stored in the repository.

The Terraform trust policy restricts the AWS deployment role to:

```text
repo:Charithaa07/Event-driven-payment-platform:environment:production
```

Use a protected GitHub `production` Environment with required reviewers before enabling real deployments.

The self-hosted runner is a network execution boundary, not an AWS credential store. It still obtains short-lived credentials through GitHub OIDC. Because this is a public repository, do not expose the private runner to arbitrary pull-request jobs; the AWS deployment workflow is manual, checks out `main`, and requires the protected production Environment.

### EKS workload -> AWS

The four Kubernetes workloads use separate ServiceAccounts:

```text
payment-platform-payment
payment-platform-transaction
payment-platform-audit
payment-platform-notification
```

Each ServiceAccount is associated with a separate IAM role through EKS Pod Identity. The role policies grant only the Kafka cluster/topic/group operations required by that service. Static Kafka credentials are not used.

The Kubernetes service-account token remains disabled for the application pods because these services do not call the Kubernetes API. EKS Pod Identity provides AWS credentials through its own credential path.

## Kafka on MSK Serverless

The AWS Helm profile enables the AWS MSK IAM Java client with:

```text
security.protocol=SASL_SSL
sasl.mechanism=AWS_MSK_IAM
sasl.jaas.config=software.amazon.msk.auth.iam.IAMLoginModule required;
sasl.client.callback.handler.class=software.amazon.msk.auth.iam.IAMClientCallbackHandler
```

The `aws-msk-iam-auth` runtime library is packaged in all four Spring Boot applications. These properties are enabled only in the AWS values profile, so Docker Compose and Testcontainers continue to use their existing local Kafka configuration.

## Database ownership and secrets

The architecture keeps the existing database-per-service boundary:

| Service | RDS database | Application database |
| --- | --- | --- |
| Payment | `${cluster}-payment` | `payments` |
| Transaction | `${cluster}-transaction` | `transactions` |
| Audit | `${cluster}-audit` | `audit` |
| Notification | `${cluster}-notification` | `notifications` |

Terraform enables RDS-managed master passwords. AWS stores those credentials in Secrets Manager and exposes only the secret ARN to Terraform outputs.

At deployment time, the manual GitHub workflow retrieves those four secrets with the short-lived deployment role and materializes the existing Kubernetes Secret contract (`payment-platform-secrets`). Passwords are masked in Actions logs. No database password is committed to Git, stored in Terraform variables, or embedded in a Helm values file.

For a mature production environment, replace the workflow-time materialization step with an External Secrets/Secrets Store CSI integration and a dedicated least-privilege application database user rotation process.

## Redis

Payment Service continues to treat Redis as an idempotency optimization rather than the source of truth. The AWS profile points it at ElastiCache Serverless and enables TLS. PostgreSQL remains authoritative if Redis is unavailable.

## Ingress / load balancing

`values-aws.yaml` is prepared for the AWS Load Balancer Controller (`ingress.class=alb`) but leaves Ingress disabled by default.

The current Terraform stack does **not** install the AWS Load Balancer Controller or create a public ALB. Install/configure the controller separately and enable the chart Ingress only after DNS/TLS and exposure requirements are decided. This keeps the infrastructure from pretending that an Internet-facing endpoint exists when it has not been provisioned.

## Observability on AWS

Application Micrometer/Prometheus metrics and OpenTelemetry instrumentation remain unchanged. `values-aws.yaml` expects an OTLP collector service at:

```text
http://adot-collector.monitoring.svc.cluster.local:4318/v1/traces
```

The current Terraform stack does **not** deploy an ADOT/OpenTelemetry Collector. Install one separately before enabling real OTLP export, or override the endpoint to another collector. EKS control-plane logs are already configured for CloudWatch.

A later production hardening pass can add Amazon Managed Service for Prometheus/Grafana, ADOT collector configuration, CloudWatch Container Insights, and alert routing.

## Terraform workflow

The configuration pins Terraform and the AWS provider in `versions.tf`. CI runs formatting, initialization without a remote backend, provider-schema validation, and Phase 16 security scanning without requiring AWS credentials:

```bash
terraform -chdir=deploy/aws/terraform fmt -check -diff -recursive
terraform -chdir=deploy/aws/terraform init -backend=false -input=false
terraform -chdir=deploy/aws/terraform validate
```

For an actual AWS environment, first create an S3 backend and initialize with your backend values, for example:

```bash
terraform -chdir=deploy/aws/terraform init \
  -backend-config="bucket=<terraform-state-bucket>" \
  -backend-config="key=payment-platform/prod/terraform.tfstate" \
  -backend-config="region=us-west-2" \
  -backend-config="use_lockfile=true"
```

Then review a plan before applying:

```bash
cp deploy/aws/terraform/terraform.tfvars.example deploy/aws/terraform/terraform.tfvars
terraform -chdir=deploy/aws/terraform plan -out=prod.tfplan
terraform -chdir=deploy/aws/terraform apply prod.tfplan
```

Because the EKS API is private-only, the operator applying or administering the cluster must have network reachability into the VPC when Kubernetes API access is required. Terraform can create the cluster control plane through the AWS API without `kubectl`, but subsequent Kubernetes deployment operations require the private network path.

Do not apply this example blindly. EKS, NAT gateways, four RDS instances, MSK Serverless, ElastiCache, and KMS are billable AWS resources.

## Required GitHub Environment variables

After Terraform has been applied, configure these variables on the protected GitHub Environment named `production`:

```text
AWS_REGION
AWS_DEPLOY_ROLE_ARN
EKS_CLUSTER_NAME
JWT_ISSUER_URI
JWT_JWK_SET_URI
```

`AWS_DEPLOY_ROLE_ARN`, cluster name, ECR URLs, RDS endpoints/secret ARNs, and workload-role ARNs are available as Terraform outputs.

## Private deployment runner prerequisite

Before using `.github/workflows/aws-deploy.yml`, register a hardened self-hosted runner that has all of the following:

- labels `self-hosted`, `linux`, `x64`, and `payment-platform-deploy`;
- network reachability to the EKS private API endpoint, normally from a private subnet or connected management network;
- outbound HTTPS access for GitHub Actions and package/image registries as required;
- Docker, AWS CLI v2, `kubectl`, `jq`, and Maven installed;
- no long-lived AWS deployment keys on disk.

The workflow verifies those required command-line tools before obtaining its short-lived AWS role. Treat the runner as privileged production infrastructure: patch it, isolate it, do not use it for untrusted PR code, and prefer ephemeral runner lifecycle when implementing this architecture for a real environment.

## Application deployment

The workflow `.github/workflows/aws-deploy.yml` is `workflow_dispatch` only and runs on the private `payment-platform-deploy` runner. It:

1. obtains short-lived AWS credentials through GitHub OIDC;
2. packages the four Spring Boot applications;
3. builds four hardened runtime images;
4. pushes an immutable commit-SHA (or explicitly supplied) tag to ECR;
5. configures `kubectl` for the private EKS API;
6. discovers RDS, MSK, and ElastiCache endpoints;
7. reads the RDS-managed credentials from Secrets Manager and creates/updates the Kubernetes Secret;
8. deploys the AWS Helm profile with exact image tags and managed-service endpoints;
9. uses Helm `--atomic` and waits for all four Deployment rollouts.

A failed Helm release rolls back rather than leaving a partially upgraded application release.

## Availability and scaling boundaries

Kubernetes HPA scales application pods, but application replicas are only one part of capacity planning. Production sizing must also account for:

- Kafka partition count and consumer-group parallelism;
- RDS connection pools, storage/IOPS, failover behavior, and transaction load;
- Redis throughput limits;
- EKS node capacity and pod scheduling;
- notification-provider quotas;
- NAT and cross-AZ traffic cost;
- downstream retry/DLT behavior during dependency failures.

The reference configuration uses two application nodes and Multi-AZ RDS by default to demonstrate availability boundaries. Instance sizes are intentionally small defaults for a portfolio environment and must be load-tested before production use.

## Correctness guarantees remain application-level

Moving to AWS does not turn at-least-once processing into exactly-once processing. The same invariants remain authoritative:

- Payment Service commits payment state and outbox intent atomically in PostgreSQL.
- Outbox publication can be repeated.
- Transaction, Audit, and Notification consumers remain idempotent at their database boundaries.
- DLT replay can redeliver records.
- Notification provider delivery still has a crash-after-provider-acceptance boundary and therefore forwards a stable provider idempotency key.

AWS supplies managed availability, identity, networking, and service primitives; it does not replace those application correctness mechanisms.
