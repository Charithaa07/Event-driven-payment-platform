# Architecture Notes

## Phase 13 system flow

1. Payment Service authenticates customer requests, applies customer-scoped idempotency, and commits a payment plus `payments.created.v1` outbox row atomically.
2. The outbox relay claims rows with `FOR UPDATE SKIP LOCKED`, commits the claim, then publishes outside the database transaction.
3. Kafka fan-out uses independent consumer groups: Transaction updates business state, Audit preserves immutable evidence, and Notification creates durable delivery work.
4. Transaction Service writes its transaction plus `processed_events` deduplication state atomically; failures receive bounded retry and durable DLT recovery.
5. Audit Service validates the event contract, computes a SHA-256 digest, and inserts one append-only row; malformed input is isolated to the audit DLT.
6. Notification Service inserts one durable delivery request per `(source_event_id, channel)`, then a leased dispatcher performs provider calls outside the claim transaction.
7. All four services expose Micrometer telemetry and optional OpenTelemetry/OTLP traces.
8. Kubernetes runs each service as an independently scalable Deployment behind a ClusterIP Service. Helm owns application compute/configuration while databases, Kafka, Redis, identity, and telemetry backends remain external dependencies.

## Runtime architecture

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

Helm: Deployments + Services + ConfigMap + Secret references + HPA + PDB + optional Ingress
External infrastructure: PostgreSQL + Kafka + Redis + OIDC/JWKS + OTLP backend
```

Each service owns its own database and never reads another service's tables. Consumer-group independence means one downstream service can be unavailable without gating another consumer's work.

## Payment correctness boundary

PostgreSQL remains authoritative for customer-scoped idempotency. Redis is an optimization, and the transactional outbox is the durable boundary between payment state and Kafka intent. Outbox publication remains intentionally at least once.

## Transaction processing and DLT recovery

Transaction Service consumes `payments.created.v1` with at-least-once semantics. New events create a business transaction and `processed_events` marker in one database transaction; duplicate event IDs become no-ops. Retryable failures receive bounded retry, then durable DLT indexing and secured replay.

## Audit Service correctness model

Audit Service preserves immutable integration-boundary evidence rather than mutable business state. Logical event ID and Kafka source position are unique, a PostgreSQL trigger rejects `UPDATE` and `DELETE`, and event-detail reads recompute a SHA-256 digest. This is integrity evidence, not cryptographic non-repudiation.

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

Phase 13 introduces a deployment boundary without changing service correctness semantics.

### Application compute only

The Helm chart deliberately does not create PostgreSQL, Kafka, Redis, OIDC, or tracing infrastructure. Those endpoints are supplied through values and a shared runtime ConfigMap. Database usernames/passwords are referenced from an existing Kubernetes Secret. This keeps the chart cloud-neutral and allows a later AWS mapping to RDS, MSK, ElastiCache, an external identity provider, and a managed/hosted telemetry stack.

### Availability and rollout

Each application has:

- a Deployment and internal ClusterIP Service;
- rolling updates with `maxUnavailable: 0` and `maxSurge: 1`;
- startup, readiness, and liveness probes using Actuator health groups;
- graceful Spring shutdown within the pod termination window;
- resource requests/limits;
- `autoscaling/v2` HPA policies;
- a PodDisruptionBudget;
- hostname topology spreading.

HPA replica growth does not imply unlimited Kafka throughput. Effective consumer parallelism still depends on partition count, PostgreSQL capacity, connection pools, and notification-provider quotas.

### Container security

The shared runtime image and pod spec use a non-root UID/GID, dropped Linux capabilities, `allowPrivilegeEscalation: false`, `RuntimeDefault` seccomp, and a read-only root filesystem. A writable `emptyDir` is mounted at `/tmp` for JVM compatibility. Service-account tokens are not mounted because the applications do not call the Kubernetes API.

### Configuration and secrets

Non-secret runtime settings are exposed through a ConfigMap. Database credential keys are loaded from an existing Secret, which can be created by a deployment system, External Secrets operator, or Secrets Store CSI integration. Real credentials are never committed to the repository.

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
```

All four services validate configured JWT signature/JWKS, issuer, audience, timing, and a non-empty subject. Kubernetes ServiceAccounts are not used as an application authentication substitute.

## Observability plane

Prometheus/Micrometer metrics cover outbox publication, transaction processing/DLT behavior, audit ingestion, and notification ingestion/provider attempts. OpenTelemetry export remains opt-in. HTTP, Kafka, scheduled outbox, and later provider-dispatch work can form separate traces where trace context is not persisted across durable boundaries.

## Deployment verification

CI validates the deployment artifacts as executable infrastructure rather than static examples:

- `helm lint` validates the chart;
- `helm template` renders the default chart and CI asserts four Deployments, Services, HPAs, and PDBs;
- the full Maven reactor runs through `verify`;
- each packaged service jar is placed into the shared hardened runtime image, proving all four image contracts build.

Application Testcontainers suites continue to verify their database/Kafka/Redis correctness boundaries independently of Kubernetes packaging.

## Future expansion

The next infrastructure milestone is an AWS deployment architecture and cloud delivery pipeline. The Kubernetes values should map external dependencies to managed cloud services rather than introducing stateful database/Kafka workloads into the application chart. A real email/SMS provider adapter remains a swappable edge component with credentials supplied through the secret-management boundary.
