# Architecture Notes

## Phase 9 system flow

1. A client obtains an access token from an external OAuth2/OIDC provider.
2. Payment Service validates JWT signature, issuer, audience, timing, subject, and OAuth scopes.
3. JWT `sub` is the trusted customer identity; payment reads and idempotency are scoped to that customer.
4. Redis provides the idempotency fast path while PostgreSQL remains authoritative.
5. A new payment and its `payments.created.v1` outbox event commit atomically.
6. The outbox relay claims work with `FOR UPDATE SKIP LOCKED`, releases the DB transaction, then publishes to Kafka.
7. Transaction Service consumes the event and commits the business transaction plus `processed_events` marker atomically.
8. Retryable Kafka failures receive two retries; exhausted or malformed events go to `payments.created.v1.DLT`.
9. springdoc generates the Payment Service OpenAPI contract and Swagger UI from runtime API metadata.
10. Micrometer exposes framework and domain metrics through Prometheus endpoints.
11. OpenTelemetry exports sampled traces over OTLP when tracing export is enabled.
12. Kafka producer/listener observation is enabled so normal Kafka records can carry tracing context.
13. Prometheus, Tempo, and Grafana form the local observability plane.

## Runtime architecture

```text
OAuth2/OIDC Provider
        |
        | JWKS
        v
Client -> Payment Service -> Redis
              |
              +-> Payment PostgreSQL
                       |
                       `-> Outbox
                            |
                    SKIP LOCKED claim
                            |
                            v
                      Outbox Relay
                            |
                            v
                          Kafka
                            |
                            v
                    Transaction Service
                       |            |
                       v            v
              Transaction DB      DLT

Payment Service ------ Prometheus scrape ------+
Transaction Service -- Prometheus scrape ------+--> Grafana
Payment Service ------ OTLP traces ------------+--> Tempo --> Grafana
Transaction Service -- OTLP traces ------------+
```

## Security boundary

Payment Service is a stateless OAuth2 Resource Server. It does not issue credentials.

```text
JWT
 |
 +-- signature via JWKS
 +-- issuer
 +-- audience
 +-- expiration/timing
 +-- non-empty subject
 |
 v
scope authorization
 +-- payments:write -> POST /api/v1/payments
 +-- payments:read  -> GET /api/v1/payments/{id}
 `-- ops:read       -> metrics / Prometheus by default
```

`/actuator/health`, `/actuator/info`, OpenAPI JSON/YAML, and Swagger UI remain public. `/actuator/prometheus` is protected by `ops:read` unless the explicit local-development setting `OBSERVABILITY_PUBLIC_PROMETHEUS=true` is enabled. That switch is intended only for the local unauthenticated Prometheus container.

## Customer-scoped idempotency

```text
JWT sub = customer-A
Idempotency-Key = checkout-42
        |
        v
Redis hashed customer/key namespace
  |-- hit --> compare original request --> return / 409
  `-- miss
       |
       v
PostgreSQL WHERE customer_id = customer-A
              AND idempotency_key = checkout-42
       |-- existing --> compare --> return / 409
       `-- absent
            |
            v
INSERT ... ON CONFLICT (customer_id, idempotency_key) DO NOTHING
```

Two customers may use the same textual idempotency key independently. For one customer, reusing a key with a different amount or currency returns `409 Conflict`.

## Transactional outbox

```text
payment transaction
  +-- payment row
  `-- PENDING outbox row
          |
          v
claim transaction
  SELECT claimable rows
  FOR UPDATE SKIP LOCKED
  mark PROCESSING + claimed_at
COMMIT
          |
          v
Kafka I/O outside DB lock
  |-- success --> PUBLISHED
  `-- failure --> bounded backoff --> FAILED after max attempts
```

The relay is intentionally **at least once**. A crash after Kafka acknowledges a send but before PostgreSQL records `PUBLISHED` can produce redelivery, so Transaction Service deduplicates using the immutable event ID and durable `processed_events` state.

## Consumer recovery

```text
payments.created.v1
        |
        v
Transaction Service
  |-- new event ------> transaction + processed_events
  |-- duplicate -----> no duplicate business write
  |-- retryable -----> retry 1 -> retry 2 -> DLT
  `-- malformed -----> DLT without useless retries
```

## API contract boundary

springdoc derives the Payment Service OpenAPI document from controller metadata, validation constraints, schemas, and explicit operation annotations. The contract documents bearer authentication, scopes, `Idempotency-Key`, ownership semantics, examples, and the `400 / 401 / 403 / 404 / 409` response model. Integration tests inspect `/v3/api-docs` to catch contract drift.

## Observability plane

### Metrics

Both services use Micrometer and the Prometheus registry. Common `application` and `environment` tags make multi-service queries explicit.

Payment Service domain metrics:

- `payments.outbox.events` with `status=pending|processing|failed`
- `payments.outbox.publish.events` with `outcome=success|failure`
- `payments.outbox.publish.latency`

Transaction Service domain metrics:

- `transactions.payment.events` with `outcome=received|created|duplicate|malformed`
- `transactions.payment.processing.latency` with `outcome=created|duplicate`
- `transactions.kafka.dlt`

Framework telemetry includes HTTP server metrics, JVM/runtime metrics, datasource instrumentation, and Kafka observations. Histograms are enabled for the latency series used in p95 dashboard queries.

### Tracing

Both services include Spring Boot OpenTelemetry support. Trace export is opt-in through:

```text
OTEL_TRACING_ENABLED=true
OTEL_EXPORTER_OTLP_TRACES_ENDPOINT=http://localhost:4318/v1/traces
TRACING_SAMPLING_PROBABILITY=1.0
```

Tempo receives OTLP HTTP traces locally. Grafana is provisioned with Tempo and Prometheus data sources.

### Important outbox trace boundary

The transactional outbox currently stores the business event payload but **does not persist the original HTTP trace context**. The original payment request therefore commits before a later scheduled relay starts publishing the outbox event. Kafka observation can propagate context from the relay-produced record to Transaction Service, but that relay trace should not be presented as a continuous child of the earlier HTTP request.

Persisting W3C trace context alongside the outbox record would be a separate future design choice. Keeping this limitation explicit avoids misleading trace topology.

## Local observability profile

`docker compose --profile observability up -d` provisions:

- Prometheus on `localhost:9090`
- Grafana on `localhost:3000`
- Tempo query/API on `localhost:3200`
- Tempo OTLP HTTP receiver on `localhost:4318`

Grafana automatically loads Prometheus and Tempo data sources plus the `Event-Driven Payment Platform` dashboard. Anonymous admin access exists only in this developer profile.

## Verification

CI performs three layers of verification:

1. `docker compose --profile observability config` validates the composed infrastructure definition.
2. Python parses the provisioned Grafana dashboard JSON.
3. `mvn --batch-mode test` runs the full unit and Testcontainers suite.

The Payment Service integration suite also requests `/actuator/prometheus` through the Spring Security filter chain with `ops:read` and checks that the custom outbox metric family is present.

The existing PostgreSQL/Redis tests continue to cover concurrent idempotency, rollback behavior, customer isolation, outbox claiming, security, and OpenAPI. Transaction Service continues to use real Kafka + PostgreSQL to verify duplicate delivery and DLT behavior.

## Remaining platform work

The next high-value milestones are an operational DLT replay/recovery workflow, service expansion (notification/audit), Kubernetes/Helm deployment definitions, and an AWS deployment architecture.
