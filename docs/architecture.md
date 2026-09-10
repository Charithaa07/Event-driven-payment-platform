# Architecture Notes

## Phase 11 system flow

1. Payment Service authenticates customer requests, applies customer-scoped idempotency, and commits a payment plus `payments.created.v1` outbox row atomically.
2. The outbox relay claims rows with `FOR UPDATE SKIP LOCKED`, commits the claim, then publishes outside the database transaction.
3. Kafka fan-out is implemented with independent consumer groups: Transaction Service updates business state while Audit Service records immutable evidence.
4. Transaction Service atomically writes its transaction plus durable `processed_events` deduplication state.
5. Transaction failures receive bounded retries and then move to `payments.created.v1.DLT`; DLT records are indexed for secured operational replay.
6. Audit Service independently consumes `payments.created.v1`, validates the event contract, computes a SHA-256 record digest, and inserts one append-only audit row.
7. Audit ingestion is idempotent by both logical `event_id` and Kafka `(topic, partition, offset)`.
8. PostgreSQL enforces Audit Service immutability with a trigger that rejects `UPDATE` and `DELETE` on `audit_events`.
9. Audit query endpoints expose payment timelines and detailed evidence only to tokens carrying `audit:read`.
10. Malformed/unrecoverable audit ingestion is isolated to `payments.created.v1.audit.DLT` instead of blocking the primary consumer indefinitely.
11. Payment, Transaction, and Audit services expose Micrometer telemetry and can export OpenTelemetry traces over OTLP.

## Runtime architecture

```text
OAuth2/OIDC Provider
        |
        | JWKS
        +----------------+----------------+
        v                v                v
Payment Service   Transaction Service   Audit Service
     |                    |                |
     +-> Redis            +-> Tx DB        +-> Audit DB
     +-> Payment DB       |   + processed  |   append-only
            |             |   + DLT index  |   SHA-256 digest
            v             |                |
          Outbox          |                |
            |             |                |
       Outbox Relay       |                |
            |             |                |
            +----------> Kafka <-----------+
                           |
             +-------------+-------------+
             |                           |
             v                           v
      payments.created.v1        payments.created.v1.DLT
             |                           |
             +--> Audit group            +--> DLT indexer/replay
             `--> Transaction group

All services ---- Prometheus / OTLP ----> Grafana + Tempo
```

Kafka consumer groups are intentionally separate. Transaction Service availability does not gate audit persistence, and Audit Service availability does not gate business-state processing. Each service owns its own database and never reads another service's tables.

## Payment correctness boundary

```text
JWT sub + Idempotency-Key
        |
        v
Redis fast path
  |-- hit --> original response / 409 on semantic conflict
  `-- miss/failure
        |
        v
PostgreSQL (customer_id, idempotency_key)
        |
INSERT ... ON CONFLICT DO NOTHING
        |
new payment + outbox row in one transaction
```

PostgreSQL remains authoritative. Redis is an optimization. The outbox is the durable boundary between the payment database transaction and Kafka.

## Transaction processing and DLT recovery

Transaction Service consumes `payments.created.v1` with at-least-once semantics. New events create a business transaction and `processed_events` marker in one database transaction; duplicate event IDs become no-ops.

Retryable processing failures receive two retries. Exhausted or deterministic malformed records are routed to `payments.created.v1.DLT`. A dedicated indexer stores DLT Kafka position, original-record metadata, failure diagnostics, payload, and recovery state.

Operational replay uses:

```text
PENDING / FAILED
      |
      v
atomic REPLAYING claim + operator identity
      |
      v
commit DB claim
      |
      v
Kafka publish outside DB transaction
  |-- success --> REPLAYED
  `-- failure --> FAILED + last_replay_error
```

A stale `REPLAYING` claim can be reclaimed after 30 seconds. Replay remains at-least-once because a process can die after broker acknowledgement and before the final state update. Durable Transaction Service idempotency protects the business effect.

## Audit Service correctness model

Audit Service is not another projection of mutable business state. Its responsibility is to preserve evidence of events observed on the integration boundary.

### Append-only schema

Each `audit_events` row stores:

- immutable `event_id` primary key;
- event and aggregate type;
- payment aggregate ID and customer ID;
- Kafka source topic, partition, and offset;
- original JSON payload;
- event occurrence timestamp and audit recording timestamp;
- a 64-character SHA-256 record digest.

The database also enforces a unique `(source_topic, source_partition, source_offset)` constraint.

A PostgreSQL `BEFORE UPDATE OR DELETE` trigger raises an exception for every attempted mutation. Immutability is therefore enforced below the REST/service layer; accidental repository or SQL updates cannot silently rewrite audit history.

### Idempotent ingestion

```text
Kafka record
   |
   v
parse + validate PaymentCreatedEvent
   |
   v
compute SHA-256 over:
 event_id
 event_type
 aggregate_id
 customer_id
 source topic/partition/offset
 original payload
   |
   v
INSERT audit_events ... ON CONFLICT DO NOTHING
```

Two forms of duplicate are covered:

1. the same Kafka record is redelivered at the same source position;
2. the same logical `event_id` appears again at another offset, for example after a replay.

Both resolve to one immutable audit event. This is intentional: the table represents logical event evidence, not every broker delivery attempt.

### Integrity verification

`GET /api/v1/audit/events/{eventId}` recomputes the SHA-256 digest from stored immutable metadata and payload and returns `integrityValid`. The digest is evidence for accidental or unauthorized content changes; it is not presented as a substitute for cryptographic signing, external timestamping, or a blockchain/ledger system.

### Audit API exposure

```text
audit:read
   |
   +--> GET /api/v1/audit/payments/{paymentId}
   |       timeline metadata; raw payload omitted
   |
   `--> GET /api/v1/audit/events/{eventId}
           full raw payload + integrity result
```

The Audit Service is a stateless OAuth2 resource server with a logical audience of `audit-api`. Health/info and OpenAPI surfaces remain public; metrics are protected by `ops:read` unless the explicit local Prometheus development switch is enabled.

### Audit failure isolation

A malformed event is a deterministic failure and is not retried pointlessly. Other ingestion failures receive bounded retry. Unrecoverable records are published to:

```text
payments.created.v1.audit.DLT
```

This DLT is separate from Transaction Service's DLT because the two consumers have different responsibilities and failure modes.

## Security boundaries

Payment Service:

```text
payments:write -> POST /api/v1/payments
payments:read  -> GET /api/v1/payments/{id}
ops:read       -> metrics / Prometheus
```

Transaction Service:

```text
ops:read  -> inspect DLT recovery state
ops:write -> replay DLT record
ops:read  -> metrics / Prometheus
```

Audit Service:

```text
audit:read -> payment audit timeline + event detail
ops:read   -> metrics / Prometheus
```

All three services verify JWT signatures from configured JWKS and validate issuer, audience, timing, and a non-empty subject.

## Observability plane

Payment Service domain metrics include outbox backlog, Kafka publish outcomes, and publish latency. Transaction Service metrics include event outcomes, processing latency, DLT indexing/backlog, and replay outcomes. Audit Service adds:

```text
audit.payment.events{outcome=received|stored|duplicate|malformed|dead_lettered}
```

Prometheus scrapes service ports `8080`, `8081`, and `8082`. OpenTelemetry export remains opt-in. Kafka observation can propagate tracing context from the outbox relay to downstream consumers.

### Important outbox trace boundary

The outbox stores the business payload but not the original HTTP W3C trace context. The payment HTTP trace therefore ends before the scheduled relay begins. Downstream Kafka consumer traces can be linked to the relay-produced record, but the system does not claim a continuous HTTP-to-consumer trace that it does not actually persist.

## Container-backed verification

CI runs the entire Maven reactor plus infrastructure configuration validation.

Audit Service's real Kafka + PostgreSQL suite verifies:

- duplicate logical delivery creates one audit row;
- the persisted digest recomputes successfully;
- PostgreSQL itself rejects an attempted `UPDATE`;
- malformed JSON reaches `payments.created.v1.audit.DLT`;
- unauthenticated audit queries return `401`;
- tokens without `audit:read` return `403`;
- timeline responses omit raw payload;
- detail responses expose payload and a successful integrity verification;
- the generated OpenAPI contract is public and documents the Audit API.

Existing Payment and Transaction Service Testcontainers suites continue to verify payment idempotency/outbox behavior and transaction/DLT recovery behavior respectively.

## Future expansion

Phase 11 intentionally records `PAYMENT_CREATED_V1`. The same Audit Service model can ingest additional independently versioned lifecycle topics later (for example transaction-state or notification outcomes) while retaining the same append-only and integrity rules.
