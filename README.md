# Event-Driven Payment Platform

A portfolio-grade payment backend built to demonstrate production concerns beyond CRUD: **authenticated APIs, concurrency-safe idempotency, transactional messaging, at-least-once delivery, consumer deduplication, bounded recovery, operational replay, immutable audit evidence, asynchronous provider delivery, observability, container hardening, Kubernetes deployment, and AWS production infrastructure**.

> Status: **Phase 14** — Payment, Transaction, Audit, and Notification run as independent Spring Boot services with separate datastores. The platform now includes hardened container images, Helm-managed Kubernetes deployment, and a validated AWS production reference architecture using EKS, MSK Serverless, RDS PostgreSQL, ElastiCache Redis, ECR, Pod Identity, Secrets Manager, and GitHub Actions OIDC delivery.

## Architecture

```mermaid
flowchart LR
    IDP[OAuth2 / OIDC Provider] -->|JWKS| P[Payment Service]
    IDP -->|JWKS| T[Transaction Service]
    IDP -->|JWKS| A[Audit Service]
    IDP -->|JWKS| N[Notification Service]

    C[Client] -->|Bearer JWT + REST| P
    P --> R[(Redis / ElastiCache)]
    P --> PG[(Payment PostgreSQL / RDS)]
    PG --> O[(Outbox Events)]
    O --> RLY[Outbox Relay]
    RLY --> K[(Kafka / MSK)]

    K --> T
    K --> A
    K --> N

    T --> TG[(Transaction PostgreSQL / RDS)]
    T --> DLT[Transaction DLT + replay]
    A --> AG[(Audit PostgreSQL / RDS)]
    A --> ADLT[Audit DLT]
    N --> NG[(Notification PostgreSQL / RDS)]
    N --> DISP[Leased Dispatcher]
    DISP --> NP[Notification Provider]
    N --> NDLT[Notification DLT]

    P -. metrics / traces .-> OBS[Prometheus + OpenTelemetry]
    T -. metrics / traces .-> OBS
    A -. metrics / traces .-> OBS
    N -. metrics / traces .-> OBS
```

### AWS production reference architecture

```text
GitHub Actions
     |
     | OIDC -> short-lived AWS credentials
     v
AWS deploy IAM role
     |
     +--> ECR immutable service images
     +--> EKS namespace-scoped deployment access
     +--> Secrets Manager read for RDS-managed credentials
     `--> managed endpoint discovery

                       AWS VPC / two AZs
              +--------------------------------+
              |                                |
              |          Amazon EKS            |
              |  payment / transaction /       |
              |  audit / notification pods     |
              |      |             |           |
              |      | Pod Identity|           |
              |      v             v           |
              |   MSK Serverless   ElastiCache |
              |      |                         |
              |  RDS PostgreSQL x4             |
              +--------------------------------+

Each service has its own Kubernetes ServiceAccount and IAM role.
MSK uses IAM/SASL; no static Kafka credentials are stored.
RDS master credentials are managed by AWS Secrets Manager.
```

## Engineering highlights

- **Java 17 + Spring Boot 4** multi-service Maven project
- **OAuth2/JWT Resource Servers** with JWKS signature validation, issuer/audience/timing checks, and scope authorization
- Customer-scoped PostgreSQL + Redis idempotency with `Idempotency-Key`
- Concurrency-safe payment creation using PostgreSQL conflict handling
- **Transactional outbox** so payment state and event intent commit atomically
- Multi-instance outbox relay using `FOR UPDATE SKIP LOCKED`, leases, retry backoff, and terminal failure state
- Independent Kafka consumer groups for Transaction, Audit, and Notification
- Transaction consumer idempotency with durable `processed_events`
- Bounded Kafka retries, DLT indexing, and secured operational replay
- Append-only Audit Service records with SHA-256 integrity verification
- Notification ingestion deduplication plus leased asynchronous provider dispatch
- Provider retry/backoff, terminal delivery state, and stable provider idempotency keys
- Runtime-generated **OpenAPI + Swagger UI**
- **Prometheus + Micrometer + OpenTelemetry/OTLP + Grafana + Tempo**
- Testcontainers verification with real PostgreSQL, Redis, and Kafka
- Reusable non-root JVM Docker image with memory-aware JVM settings
- **Helm-managed Kubernetes Deployments, Services, HPAs, PDBs, ConfigMap/Secret boundaries, probes, rolling updates, and optional Ingress**
- **Terraform-managed AWS reference stack:** VPC, EKS, ECR, four RDS databases, MSK Serverless, ElastiCache Serverless, EKS Pod Identity, Secrets Manager integration, and CloudWatch EKS control-plane logs
- **AWS MSK IAM authentication** enabled through an environment-specific Helm profile while local/Testcontainers Kafka remains unchanged
- One workload IAM role per service with separated MSK read/write permissions
- GitHub Actions production delivery through **OIDC federation**, immutable ECR tags, Helm deployment, and rollout verification
- CI validates observability configuration, Terraform formatting/provider schema, default + AWS Helm rendering, Maven verification, and all four runtime image builds

## Core payment flow

```text
Authenticated customer + Idempotency-Key
        |
        v
customer-scoped Redis cache
  |-- HIT --> compare request --> original payment / 409
  `-- MISS / unavailable
             |
             v
PostgreSQL (customer_id, idempotency_key)
  |-- existing --> compare --> return / 409
  `-- absent
       |
       v
INSERT ... ON CONFLICT DO NOTHING
  |-- winner --> payment + outbox in one transaction
  `-- loser  --> load winner and return same result
```

The outbox relay claims rows with `FOR UPDATE SKIP LOCKED`, commits the short claim transaction, then publishes to Kafka. Delivery remains intentionally **at least once**, so downstream services keep durable idempotency boundaries.

## AWS delivery model

Terraform under `deploy/aws/terraform` defines a production reference environment. It intentionally does **not** apply automatically from CI. CI performs `fmt`, `init -backend=false`, and provider-backed `validate`, so infrastructure compatibility is checked without creating billable cloud resources.

After an operator provisions the stack, `.github/workflows/aws-deploy.yml` can be dispatched from `main` through a protected `production` GitHub Environment. The workflow:

1. exchanges GitHub's OIDC token for short-lived AWS credentials;
2. packages the four Spring Boot services;
3. builds and pushes immutable images to ECR;
4. discovers RDS, MSK, and ElastiCache endpoints;
5. reads AWS-managed RDS credentials from Secrets Manager and materializes the Kubernetes database Secret;
6. deploys the AWS Helm profile using immutable image tags;
7. waits for all four EKS rollouts to complete.

The production namespace is a platform bootstrap prerequisite. The GitHub role receives namespace-scoped EKS access rather than cluster-wide deployment administration.

### AWS workload identity and Kafka

EKS Pod Identity maps dedicated Kubernetes ServiceAccounts to one IAM role per service. The applications use the AWS MSK IAM auth library only in the AWS runtime profile. Topic administration and data permissions are split by workload: Payment publishes the source event stream; Transaction can consume/replay and publish its DLT; Audit and Notification consume the source topic and write only their own DLT topics. Explicit Spring Kafka topic declarations ensure a fresh MSK environment has the required topics.

### Managed data services

Each bounded service owns a separate RDS PostgreSQL database. Payment alone uses ElastiCache Serverless Redis as the idempotency fast path. MSK Serverless is shared only as the event transport; service state never crosses database boundaries.

See [`docs/aws.md`](docs/aws.md) for the infrastructure/runbook details and [`docs/architecture.md`](docs/architecture.md) for correctness boundaries.

## Transaction and DLT recovery

Transaction Service consumes `payments.created.v1`, writes the business transaction and `processed_events` marker atomically, and ignores duplicate event IDs. Retryable failures receive bounded retry; exhausted or malformed records move to the transaction DLT.

The DLT is indexed durably for operations. `ops:read` permits inspection and `ops:write` permits replay. Replay uses a leased `REPLAYING` claim and publishes outside the database transaction. A crash after Kafka acknowledgment can still duplicate a replay, which is safe because transaction processing remains idempotent.

## Immutable Audit Service

Audit Service independently consumes the same payment event stream. Event ID is the logical idempotency key, Kafka source position is independently unique, and PostgreSQL rejects `UPDATE` and `DELETE` on audit records. Detail reads recompute the stored SHA-256 integrity digest.

## Asynchronous Notification Service

Notification Service converts each logical payment event into one durable EMAIL delivery request. A unique `(source_event_id, channel)` constraint makes event replay idempotent.

```text
payments.created.v1
        |
        v
Notification consumer
        |
        v
Notification DB: PENDING
        |
        v
FOR UPDATE SKIP LOCKED claim
        |
        v
Provider call outside DB transaction
   | success            -> SENT
   | retryable failure  -> RETRY_PENDING + backoff
   ` permanent/exhausted -> FAILED
```

Kafka ingestion and external provider delivery are separate failure domains. A `PROCESSING` lease allows another replica to reclaim abandoned work. Because a crash can occur after a provider accepts a message but before `SENT` is persisted, the stable notification ID is forwarded as the provider idempotency key. End-to-end exactly-once provider delivery is deliberately not claimed.

## API and authorization

| Service / operation | Authorization |
| --- | --- |
| Payment `POST /api/v1/payments` | `payments:write` |
| Payment `GET /api/v1/payments/{paymentId}` | `payments:read` + JWT-sub ownership |
| Transaction `GET /api/v1/operations/dlt/**` | `ops:read` |
| Transaction `POST /api/v1/operations/dlt/{eventId}/replay` | `ops:write` |
| Audit `GET /api/v1/audit/payments/{paymentId}` | `audit:read` |
| Audit `GET /api/v1/audit/events/{eventId}` | `audit:read` |
| Notification `GET /api/v1/notifications/{notificationId}` | `notification:read` |
| Notification `GET /api/v1/notifications?paymentId=...` | `notification:read` |
| `/actuator/metrics/**` | `ops:read` |
| `/actuator/prometheus` | `ops:read` by default |
| `/actuator/health`, `/actuator/info` | public health/probe endpoints |
| `/v3/api-docs`, `/swagger-ui.html` | public API documentation where enabled |

Logical JWT audiences are independently configurable: `payment-api`, `transaction-ops-api`, `audit-api`, and `notification-api`.

## Observability

All four services expose Micrometer telemetry and can export traces over OTLP. Domain metrics include payment outbox publication, transaction processing/DLT recovery, audit ingestion, and notification ingestion/delivery attempts.

The local observability profile provisions Prometheus, Grafana, and Tempo. Shared environments should keep `/actuator/prometheus` protected and provide authenticated scraping instead of enabling the local unauthenticated switch.

## Run locally

Prerequisites: Java 17+, Maven, Docker, and an OAuth2/OIDC provider or test issuer exposing JWKS.

```bash
docker compose up -d

export JWT_ISSUER_URI=https://issuer.example.com/
export JWT_AUDIENCE=payment-api
export TRANSACTION_JWT_AUDIENCE=transaction-ops-api
export AUDIT_JWT_AUDIENCE=audit-api
export NOTIFICATION_JWT_AUDIENCE=notification-api
export JWT_JWK_SET_URI=https://issuer.example.com/.well-known/jwks.json

mvn spring-boot:run -pl payment-service
mvn spring-boot:run -pl transaction-service
mvn spring-boot:run -pl audit-service
mvn spring-boot:run -pl notification-service
```

Service ports are `8080` through `8083`. Local PostgreSQL instances use `5432` through `5435`.

Run the complete test suite:

```bash
mvn --batch-mode verify
```

Start local monitoring with:

```bash
docker compose --profile observability up -d
export OBSERVABILITY_PUBLIC_PROMETHEUS=true
export OTEL_TRACING_ENABLED=true
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces
```

Grafana is on `localhost:3000`, Prometheus on `localhost:9090`, and Tempo on `localhost:3200`.

## Build runtime images

Build the Spring Boot jars first:

```bash
mvn --batch-mode -DskipTests package
```

The root `Dockerfile` is intentionally shared by all four services. Each image receives exactly one packaged jar and runs as UID/GID `10001` on the JRE runtime image.

```bash
docker build --build-arg JAR_FILE=payment-service/target/payment-service-0.1.0-SNAPSHOT.jar --build-arg SERVICE_PORT=8080 -t payment-service:local .
```

Use the equivalent service jar/port for Transaction (`8081`), Audit (`8082`), and Notification (`8083`). CI builds all four images from the packaged reactor.

## Deploy with Helm

The chart is in `deploy/helm/payment-platform`. The default values remain provider-neutral. `values-aws.yaml` overlays managed AWS dependency settings, TLS Redis, MSK IAM/SASL, and dedicated workload ServiceAccounts.

```bash
helm lint deploy/helm/payment-platform
helm template payment-platform deploy/helm/payment-platform --namespace payments
helm template payment-platform deploy/helm/payment-platform --namespace payments -f deploy/helm/payment-platform/values-aws.yaml
```

Database usernames/passwords are read from the pre-existing `payment-platform-secrets` Kubernetes Secret. No real credentials or secret values are committed to the repository.

See [`docs/kubernetes.md`](docs/kubernetes.md) for the provider-neutral deployment model and [`docs/aws.md`](docs/aws.md) for the AWS production reference flow.

## Verification

**Payment Service:** PostgreSQL + Redis tests cover concurrent/customer-scoped idempotency, rollback/cache behavior, outbox claiming, JWT authorization/ownership, OpenAPI, and Prometheus metrics.

**Transaction Service:** Kafka + PostgreSQL tests cover duplicate delivery, DLT indexing, secured replay, repeat-replay safety, and operational scopes.

**Audit Service:** Kafka + PostgreSQL tests verify logical duplicate events produce one record, stored hashes verify successfully, PostgreSQL rejects audit mutation, malformed records reach the audit DLT, audit APIs enforce `audit:read`, and OpenAPI remains public.

**Notification Service:** Kafka + PostgreSQL tests verify logical duplicate events create one delivery, retryable failure persists `RETRY_PENDING` and later reaches `SENT`, permanent failure reaches `FAILED`, malformed records reach the notification DLT, APIs enforce `notification:read`, and OpenAPI remains public.

**Deployment / AWS:** CI validates observability config, Terraform formatting and AWS provider schema, generic and AWS Helm rendering, the full Maven `verify` reactor, and all four service image builds. Terraform validation does not create AWS resources.

## Roadmap

- [x] Payment command API + PostgreSQL/Flyway
- [x] Redis idempotency fast path + concurrency-safe customer-scoped idempotency
- [x] Transactional outbox + multi-instance `SKIP LOCKED` relay
- [x] Idempotent Transaction Service + Kafka retries/DLT recovery
- [x] Durable DLT indexing + secured operational replay
- [x] OAuth2/JWT authentication and authorization
- [x] OpenAPI + Swagger UI
- [x] Prometheus + OpenTelemetry + Grafana/Tempo
- [x] Immutable Audit Service + dedicated datastore
- [x] Notification Service + leased retryable provider dispatch
- [x] PostgreSQL / Redis / Kafka Testcontainers verification
- [x] GitHub Actions CI
- [x] Containerized service runtime + Kubernetes/Helm deployment
- [x] **AWS production reference architecture + OIDC delivery pipeline**
- [ ] Performance, resilience, and SLO validation
- [ ] Security/SBOM/container/IaC scanning
- [ ] Real provider adapter with secret-managed credentials
- [ ] Expand audit ingestion to transaction/notification lifecycle topics

## Tech stack

**Backend:** Java 17, Spring Boot, Spring Data JPA, Spring Data Redis, Spring Kafka  
**API:** REST, OpenAPI, springdoc, Swagger UI  
**Security:** Spring Security, OAuth2 Resource Server, JWT, JWKS, scopes, AWS IAM, EKS Pod Identity, GitHub OIDC  
**Data:** PostgreSQL, Redis, Amazon RDS, Amazon ElastiCache Serverless  
**Messaging:** Apache Kafka, Amazon MSK Serverless, MSK IAM/SASL  
**Observability:** Micrometer, Prometheus, OpenTelemetry, OTLP, Tempo, Grafana, Spring Boot Actuator, CloudWatch EKS logs  
**Testing:** JUnit, Spring Security Test, Testcontainers  
**Infrastructure:** Docker, Docker Compose, Kubernetes, Helm, Terraform, Amazon EKS, ECR, RDS, MSK, ElastiCache, Secrets Manager, GitHub Actions

## Repository structure

```text
event-driven-payment-platform/
├── payment-service/
├── transaction-service/
├── audit-service/
├── notification-service/
├── deploy/
│   ├── helm/payment-platform/
│   │   ├── Chart.yaml
│   │   ├── values.yaml
│   │   ├── values-aws.yaml
│   │   └── templates/
│   └── aws/terraform/
├── observability/
├── docs/
│   ├── architecture.md
│   ├── kubernetes.md
│   └── aws.md
├── Dockerfile
├── .dockerignore
├── .github/workflows/
│   ├── ci.yml
│   └── aws-deploy.yml
├── docker-compose.yml
├── pom.xml
└── README.md
```

## Design principle

Each milestone introduces a concrete production concern and documents the trade-off it solves. The repository evolves through reviewable PRs so its history demonstrates service boundaries, failure modes, correctness invariants, deployment boundaries, and executable verification rather than a one-shot code dump.
