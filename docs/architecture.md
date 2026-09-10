# Architecture Notes

## Phase 10 system flow

1. A client obtains an access token from an external OAuth2/OIDC provider.
2. Payment Service validates JWT signature, issuer, audience, timing, subject, and OAuth scopes.
3. JWT `sub` is the trusted customer identity; payment reads and idempotency are scoped to that customer.
4. Redis provides the idempotency fast path while PostgreSQL remains authoritative.
5. A new payment and its `payments.created.v1` outbox event commit atomically.
6. The outbox relay claims work with `FOR UPDATE SKIP LOCKED`, releases the DB transaction, then publishes to Kafka.
7. Transaction Service consumes the event and commits the business transaction plus `processed_events` marker atomically.
8. Retryable Kafka failures receive two retries; exhausted or malformed events go to `payments.created.v1.DLT`.
9. A dedicated DLT indexer persists each dead-letter Kafka position, failure diagnostics, payload, and recovery state in Transaction Service PostgreSQL.
10. Transaction Service also acts as an OAuth2 resource server for the operational recovery API: `ops:read` inspects DLT state and `ops:write` authorizes replay.
11. Replay uses an atomic `REPLAYING` claim with a stale-claim lease, publishes outside the DB transaction, and records the authenticated operator plus outcome.
12. springdoc generates the Payment Service OpenAPI contract and Swagger UI from runtime API metadata.
13. Micrometer exposes framework, domain, and DLT recovery metrics through Prometheus endpoints.
14. OpenTelemetry exports sampled traces over OTLP when tracing export is enabled.
15. Kafka producer/listener observation is enabled so normal Kafka records can carry tracing context.
16. Prometheus, Tempo, and Grafana form the local observability plane.

## Runtime architecture

```text
OAuth2/OIDC Provider
        |
        | JWKS
        +-------------------+
        v                   v
Client -> Payment Service   Transaction Service <--- Operator
              |                  |     ^              ops:read/write
              +-> Redis          |     |
              +-> Payment DB     |     +--- DLT recovery API
                     |           |
                     `-> Outbox  +-> Transaction DB
                          |       |      +-- processed_events
                   SKIP LOCKED    |      `-- dead_letter_events
                          |       |
                          v       |
                     Outbox Relay |
                          |       |
                          v       |
                        Kafka ----+
                          |
                          +--> payments.created.v1.DLT
                                      |
                                      v
                                  DLT Indexer

Payment Service ------ Prometheus scrape ------+
Transaction Service -- Prometheus scrape ------+--> Grafana
Payment Service ------ OTLP traces ------------+--> Tempo --> Grafana
Transaction Service -- OTLP traces ------------+
```

## Security boundary

Both HTTP-facing services are stateless OAuth2 resource servers. Neither issues credentials.

Payment Service scopes:

```text
payments:write -> POST /api/v1/payments
payments:read  -> GET /api/v1/payments/{id}
ops:read       -> metrics / Prometheus by default
```

Transaction Service operational scopes:

```text
ops:read  -> GET  /api/v1/operations/dlt[/{id}]
ops:write -> POST /api/v1/operations/dlt/{id}/replay
ops:read  -> metrics / Prometheus by default
```

`/actuator/health` and `/actuator/info` remain public. `/actuator/prometheus` is protected by `ops:read` unless the explicit local-development setting `OBSERVABILITY_PUBLIC_PROMETHEUS=true` is enabled. That switch exists only for the local unauthenticated Prometheus container.

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

## Consumer recovery and DLT indexing

```text
payments.created.v1
        |
        v
Transaction Service
  |-- new event ------> transaction + processed_events
  |-- duplicate -----> no duplicate business write
  |-- retryable -----> retry 1 -> retry 2 -> DLT
  `-- malformed -----> DLT without useless retries
                                      |
                                      v
                                DLT indexer
                                      |
                                      v
                              dead_letter_events
```

Each DLT record is uniquely indexed by `(dlt_topic, dlt_partition, dlt_offset)`, so indexer redelivery does not create duplicate recovery records. Spring Kafka DLT headers provide original topic/partition/offset/consumer-group and exception metadata when available.

## Operational replay state machine

```text
PENDING ------> REPLAYING ------> REPLAYED
   ^                |
   |                |
   +----- FAILED <--+
```

Replay does not hold a database lock while waiting for Kafka. The workflow is:

1. atomically claim `PENDING`, `FAILED`, or stale `REPLAYING` state;
2. record `replay_attempts`, `replay_claimed_at`, and the operator JWT subject;
3. commit the claim;
4. publish the original key/payload to the original source topic;
5. mark `REPLAYED` after broker acknowledgement, or `FAILED` with `last_replay_error` on failure.

A `REPLAYING` claim older than 30 seconds may be reclaimed after an instance crash. Two live operators cannot intentionally claim the same record at the same time.

Replay remains **at least once**. A crash after Kafka acknowledgement but before the `REPLAYED` update can lead to a later duplicate replay. The durable `processed_events` consumer guard makes the duplicate business effect safe.

The recovery API intentionally does not support editing a financial event payload before replay. A poison record should be replayed only after the underlying producer/data/application issue is corrected; payload mutation would require a separate audited correction workflow.

See [`dlt-recovery.md`](dlt-recovery.md) for the operational runbook.

## API contract boundary

springdoc derives the Payment Service OpenAPI document from controller metadata, validation constraints, schemas, and explicit operation annotations. The contract documents bearer authentication, scopes, `Idempotency-Key`, ownership semantics, examples, and the `400 / 401 / 403 / 404 / 409` response model. Integration tests inspect `/v3/api-docs` to catch contract drift.

## Observability plane

Payment Service domain metrics:

- `payments.outbox.events` with `status=pending|processing|failed`
- `payments.outbox.publish.events` with `outcome=success|failure`
- `payments.outbox.publish.latency`

Transaction Service domain/recovery metrics:

- `transactions.payment.events` with `outcome=received|created|duplicate|malformed`
- `transactions.payment.processing.latency` with `outcome=created|duplicate`
- `transactions.kafka.dlt`
- `transactions.kafka.dlt.indexed`
- `transactions.kafka.dlt.backlog`
- `transactions.kafka.dlt.replay` with `outcome=success|failure`

Framework telemetry includes HTTP server metrics, JVM/runtime metrics, datasource instrumentation, and Kafka observations. Histograms are enabled for latency series used in dashboard queries.

## Important outbox trace boundary

The transactional outbox stores the business event payload but **does not persist the original HTTP trace context**. The original payment request therefore commits before a later scheduled relay starts publishing the outbox event. Kafka observation can propagate context from the relay-produced record to Transaction Service, but that relay trace should not be presented as a continuous child of the earlier HTTP request.

Persisting W3C trace context alongside the outbox record would be a separate future design choice.

## Verification

CI validates the Compose/Grafana configuration and runs the complete Maven/Testcontainers reactor.

Payment Service integration coverage includes PostgreSQL/Redis idempotency, rollback behavior, customer isolation, outbox claiming, OAuth2 authorization, OpenAPI, and protected Prometheus metrics.

Transaction Service integration coverage now verifies:

- duplicate source events create one business transaction;
- malformed source events reach the DLT;
- DLT records are durably indexed with failure metadata;
- a valid DLT record can be replayed into the original source topic and processed;
- a `REPLAYED` record is idempotent on repeated replay requests;
- DLT inspection requires `ops:read`;
- replay requires `ops:write`.

## Remaining platform work

The next high-value milestones are service expansion (notification/audit), Kubernetes/Helm deployment definitions, and an AWS deployment architecture.
