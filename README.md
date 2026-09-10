# Event-Driven Payment Platform

A portfolio-grade payment backend built to demonstrate production concerns beyond CRUD: **authenticated APIs, concurrency-safe idempotency, transactional messaging, at-least-once delivery, consumer deduplication, bounded recovery, executable API contracts, metrics, and distributed tracing**.

> Status: **Phase 9** — the secured Payment Service and idempotent Transaction Service now include OpenAPI/Swagger, Prometheus metrics, OpenTelemetry/OTLP tracing, Kafka observation, custom reliability metrics, a provisioned Grafana dashboard, and container-backed CI verification.

## Architecture

```mermaid
flowchart LR
    IDP[OAuth2 / OIDC Provider] -->|JWKS| P[Payment Service]
    C[Client] -->|Bearer JWT + REST| P
    DOC[Swagger UI / OpenAPI] --> P
    P --> R[(Redis)]
    P --> PG[(Payment PostgreSQL)]
    PG --> O[(Outbox Events)]
    O --> RLY[Outbox Relay]
    RLY --> K[(Kafka)]
    K --> T[Transaction Service]
    T --> TG[(Transaction PostgreSQL)]
    T --> PE[(Processed Events)]
    T --> DLT[payments.created.v1.DLT]

    P -. Prometheus metrics .-> PROM[Prometheus]
    T -. Prometheus metrics .-> PROM
    P -. OTLP traces .-> TEMPO[Tempo]
    T -. OTLP traces .-> TEMPO
    PROM --> G[Grafana]
    TEMPO --> G
```

## Engineering highlights

- **Java 17 + Spring Boot 4** multi-service Maven project
- **OAuth2/JWT Resource Server** with JWKS signature validation, issuer/audience/timing validation, and scope authorization
- `payments:write`, `payments:read`, and `ops:read` authorization boundaries
- Customer identity derived from JWT `sub`; callers cannot spoof ownership through request JSON
- Customer-scoped `Idempotency-Key` semantics in PostgreSQL and Redis
- Concurrency-safe payment creation using PostgreSQL `ON CONFLICT DO NOTHING`
- `409 Conflict` when the same customer reuses an idempotency key with different payment semantics
- Redis idempotency fast path with PostgreSQL as the durability/source-of-truth fallback
- **Transactional outbox** so payment state and event intent commit atomically
- Multi-instance outbox claiming with `FOR UPDATE SKIP LOCKED`, leases, retry backoff, and terminal failure state
- Kafka publication outside the short claim transaction
- Separate Transaction Service database and durable `processed_events` consumer idempotency
- Original delivery + two Kafka retries, then `payments.created.v1.DLT`
- Runtime-generated **OpenAPI contract + Swagger UI** with bearer auth, validation, examples, idempotency semantics, and documented errors
- **Prometheus + Micrometer** metrics for HTTP/JVM/Kafka plus domain reliability metrics
- **OpenTelemetry/OTLP** tracing with Kafka observation enabled
- Provisioned **Prometheus + Grafana + Tempo** local observability profile
- Testcontainers integration coverage with real PostgreSQL, Redis, and Kafka
- GitHub Actions CI validates Maven tests, Compose configuration, and Grafana dashboard JSON

## Request and reliability flow

```text
Authenticated customer + Idempotency-Key
        |
        v
customer-scoped Redis cache
  |-- HIT --> compare request --> original payment / 409
  |
  `-- MISS / unavailable
             |
             v
PostgreSQL (customer_id, idempotency_key)
  |-- existing --> compare --> return / 409
  |
  `-- absent
       |
       v
INSERT ... ON CONFLICT DO NOTHING
  |-- winner --> payment + outbox in one transaction
  `-- loser  --> load winner and return same result
```

Redis is an optimization, not the correctness boundary. New cache entries are written after the database transaction commits, and the durable uniqueness constraint remains authoritative during cache misses, outages, and concurrent requests.

## Outbox and consumer flow

```text
payment + PENDING outbox row
        |
        v
FOR UPDATE SKIP LOCKED
mark PROCESSING + lease
COMMIT
        |
        v
Kafka publish
  |-- success --> PUBLISHED
  `-- failure --> bounded backoff --> FAILED after max attempts
        |
        v
payments.created.v1
        |
        v
Transaction Service
  |-- new event --> transaction + processed_events in one DB transaction
  |-- duplicate --> no duplicate business transaction
  `-- failure --> retry 1 --> retry 2 --> DLT
```

The messaging guarantee is intentionally **at least once**. A producer-side retry or crash can cause redelivery, so consumer idempotency is part of the design rather than an optional optimization.

## API and security

The Payment Service validates bearer access tokens issued by an external OAuth2/OIDC provider. It does not mint credentials.

| Operation | Authorization |
| --- | --- |
| `POST /api/v1/payments` | `payments:write` |
| `GET /api/v1/payments/{paymentId}` | `payments:read` + JWT-sub ownership |
| `/actuator/metrics/**` | `ops:read` |
| `/actuator/prometheus` | `ops:read` by default |
| `/actuator/health`, `/actuator/info` | public probe endpoints |
| `/v3/api-docs`, `/swagger-ui.html` | public documentation |

A payment owned by another JWT subject resolves as `404 Not Found` rather than revealing another customer's resource.

### OpenAPI

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`
- OpenAPI YAML: `http://localhost:8080/v3/api-docs.yaml`

The generated contract documents JWT bearer authentication, required scopes, `Idempotency-Key`, schema validation, examples, and `400 / 401 / 403 / 404 / 409` behavior. Integration tests inspect the generated document so controller/security changes cannot silently remove important contract metadata.

## Observability

Both services export Micrometer metrics to Prometheus and can export traces over OTLP to Tempo.

### Framework telemetry

- HTTP request count and latency histograms
- JVM/runtime metrics
- datasource metrics
- Kafka producer/listener observations
- service/environment common tags
- trace-linked Prometheus exemplars for sampled traces

### Domain reliability telemetry

Payment Service exposes:

- `payments.outbox.events{status=pending|processing|failed}` — current outbox backlog
- `payments.outbox.publish.events{outcome=success|failure}` — publish outcomes
- `payments.outbox.publish.latency` — Kafka publication latency

Transaction Service exposes:

- `transactions.payment.events{outcome=received|created|duplicate|malformed}`
- `transactions.payment.processing.latency{outcome=created|duplicate}`
- `transactions.kafka.dlt` — dead-letter publications

The provisioned Grafana dashboard combines request rate, p95 API latency, JVM heap, outbox backlog, publish failures, transaction outcomes, processing latency, and DLT activity.

### Trace boundary

Kafka observation is enabled on the producer template and listener container. However, the transactional outbox currently persists the **business event payload only**. The original HTTP transaction finishes before the relay later reads that outbox row, so the HTTP request trace and the asynchronous outbox-relay trace are separate unless trace context is explicitly persisted with the outbox event in a future enhancement. This limitation is documented rather than implying false end-to-end continuity.

## Run locally

Prerequisites: Java 17+, Maven, Docker, and an OAuth2/OIDC provider (or test issuer) with a JWKS endpoint.

Start application infrastructure:

```bash
docker compose up -d
```

Configure JWT validation and start the services:

```bash
export JWT_ISSUER_URI=https://issuer.example.com/
export JWT_AUDIENCE=payment-api
export JWT_JWK_SET_URI=https://issuer.example.com/.well-known/jwks.json

mvn spring-boot:run -pl payment-service
mvn spring-boot:run -pl transaction-service
```

Run all tests:

```bash
mvn --batch-mode test
```

### Run local observability

```bash
docker compose --profile observability up -d

export OBSERVABILITY_PUBLIC_PROMETHEUS=true
export OTEL_TRACING_ENABLED=true
export OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces
export TRACING_SAMPLING_PROBABILITY=1.0
```

Then start both application services. Local tools:

- Grafana: `http://localhost:3000`
- Prometheus: `http://localhost:9090`
- Tempo: `http://localhost:3200`

`OBSERVABILITY_PUBLIC_PROMETHEUS=true` exists only so the local unauthenticated Prometheus container can scrape Payment Service. **Leave it false in shared/production environments** and use an authenticated or otherwise protected scrape path.

See [`observability/README.md`](observability/README.md) for the local monitoring workflow.

## Verification

The integration suite exercises real infrastructure boundaries rather than only mocks.

**Payment Service:** PostgreSQL + Redis tests cover concurrent idempotency, customer isolation, conflicting retries, transaction rollback/cache behavior, outbox persistence/claiming, HTTP authentication/authorization, ownership, OpenAPI generation, and the protected Prometheus endpoint with custom outbox metrics.

**Transaction Service:** Kafka + PostgreSQL tests verify duplicate delivery produces one business transaction and malformed events reach the dead-letter topic.

CI additionally validates the Docker Compose observability profile and parses the provisioned Grafana dashboard as JSON before running the full Maven reactor.

## Roadmap

- [x] Payment command API
- [x] PostgreSQL + Flyway
- [x] Redis idempotency fast path
- [x] Concurrent and customer-scoped idempotency
- [x] Transactional outbox
- [x] Multi-instance `SKIP LOCKED` outbox claiming
- [x] Outbox leases, bounded retry/backoff, terminal failure state
- [x] Idempotent Transaction Service consumer
- [x] Kafka retries + dead-letter recovery
- [x] PostgreSQL / Redis / Kafka Testcontainers tests
- [x] OAuth2/JWT authentication and authorization
- [x] JWT-derived customer ownership
- [x] OpenAPI contract + Swagger UI
- [x] Prometheus metrics + custom reliability telemetry
- [x] OpenTelemetry/OTLP tracing + Kafka observations
- [x] Provisioned Grafana + Tempo local stack
- [x] GitHub Actions CI
- [ ] DLT replay / operational recovery endpoint
- [ ] Notification service
- [ ] Audit service
- [ ] Kubernetes manifests / Helm
- [ ] AWS deployment architecture

## Tech stack

**Backend:** Java 17, Spring Boot, Spring Data JPA, Spring Data Redis, Spring Kafka  
**API:** REST, OpenAPI, springdoc, Swagger UI  
**Security:** Spring Security, OAuth2 Resource Server, JWT, JWKS, scopes  
**Data:** PostgreSQL, Redis  
**Messaging:** Apache Kafka  
**Observability:** Micrometer, Prometheus, OpenTelemetry, OTLP, Tempo, Grafana, Spring Boot Actuator  
**Testing:** JUnit, Mockito, Spring Security Test, Testcontainers  
**Infrastructure:** Docker Compose, GitHub Actions

## Repository structure

```text
event-driven-payment-platform/
├── payment-service/
├── transaction-service/
├── observability/
│   ├── prometheus/
│   ├── tempo/
│   └── grafana/
├── docs/
├── .github/workflows/ci.yml
├── docker-compose.yml
├── pom.xml
└── README.md
```

## Design principle

Each milestone introduces a concrete production concern and documents the trade-off it solves. The repository evolves through reviewable PRs so the history demonstrates engineering decisions, failure modes, and verification rather than a one-shot code dump.
