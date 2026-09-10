# Architecture Notes

## Phase 12 system flow

1. Payment Service authenticates customer requests, applies customer-scoped idempotency, and commits a payment plus `payments.created.v1` outbox row atomically.
2. The outbox relay claims rows with `FOR UPDATE SKIP LOCKED`, commits the claim, then publishes outside the database transaction.
3. Kafka fan-out uses independent consumer groups: Transaction Service updates business state, Audit Service preserves immutable evidence, and Notification Service creates durable delivery work.
4. Transaction Service atomically writes its transaction plus durable `processed_events` deduplication state.
5. Transaction failures receive bounded retries and then move to `payments.created.v1.DLT`; DLT records are indexed for secured operational replay.
6. Audit Service validates the event contract, computes a SHA-256 record digest, and inserts one append-only row; malformed audit input is isolated to `payments.created.v1.audit.DLT`.
7. Notification Service inserts one `PENDING` EMAIL delivery per logical `(source_event_id, channel)` using PostgreSQL `ON CONFLICT DO NOTHING`.
8. A scheduled notification dispatcher claims due work with `FOR UPDATE SKIP LOCKED`, changes rows to `PROCESSING`, and commits before making the provider call.
9. Provider success persists `SENT`; retryable failures persist `RETRY_PENDING` with bounded exponential backoff; permanent or exhausted failures persist `FAILED`.
10. Stale `PROCESSING` rows are reclaimable after the configured lease timeout, allowing crash recovery across service instances.
11. Notification Kafka-ingestion failures use the independent `payments.created.v1.notification.DLT`; provider delivery failures remain in the Notification database because they occur after ingestion has succeeded.
12. Payment, Transaction, Audit, and Notification services expose Micrometer telemetry and can export OpenTelemetry traces over OTLP.

## Runtime architecture

```text
OAuth2/OIDC Provider
        |
        | JWKS
        +----------------+----------------+----------------+
        v                v                v                v
Payment Service   Transaction Service   Audit Service   Notification Service
     |                    |                |                |
     +-> Redis            +-> Tx DB        +-> Audit DB     +-> Notification DB
     +-> Payment DB       |   + processed  |   append-only  |   durable states
            |             |   + DLT index  |   SHA-256      |       |
            v             |                |                |       v
          Outbox          |                |                |   Leased dispatcher
            |             |                |                |       |
       Outbox Relay       |                |                |       v
            |             |                |                |   Provider adapter
            +-------------------------> Kafka <-------------+
                                      |
                   +------------------+------------------+
                   |                  |                  |
                   v                  v                  v
             Transaction group    Audit group      Notification group
                   |                  |                  |
                   v                  v                  v
        payments.created.v1.DLT  .audit.DLT       .notification.DLT

All services ---- Prometheus / OTLP ----> Grafana + Tempo
```

Each service owns its own database and never reads another service's tables. Consumer-group independence means one downstream service can be unavailable without gating another consumer's work.

## Payment correctness boundary

PostgreSQL remains authoritative for customer-scoped idempotency. Redis is an optimization, and the transactional outbox is the durable boundary between payment state and Kafka intent.

## Transaction processing and DLT recovery

Transaction Service consumes `payments.created.v1` with at-least-once semantics. New events create a business transaction and `processed_events` marker in one database transaction; duplicate event IDs become no-ops. Retryable failures receive bounded retry, then durable DLT indexing and secured replay.

## Audit Service correctness model

Audit Service preserves immutable integration-boundary evidence rather than mutable business state. Logical event ID and Kafka source position are unique, a PostgreSQL trigger rejects `UPDATE` and `DELETE`, and event-detail reads recompute a SHA-256 digest. This is integrity evidence, not cryptographic non-repudiation.

## Notification Service correctness model

Notification delivery has two separate failure domains: Kafka ingestion and the external provider boundary. Keeping them separate avoids treating a temporary email/SMS provider outage as a poisoned Kafka record.

### Idempotent ingestion

```text
payments.created.v1
      |
      v
parse + validate PaymentCreatedEvent
      |
      v
INSERT notification_deliveries
  source_event_id
  channel=EMAIL
  status=PENDING
  attempt_count=0
  next_attempt_at=now
ON CONFLICT (source_event_id, channel) DO NOTHING
```

The unique `(source_event_id, channel)` constraint is the durable logical deduplication boundary. A Kafka replay can create another broker delivery without creating another notification request.

### Leased multi-instance dispatch

```text
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

The provider call intentionally runs outside the database transaction. This avoids holding row locks during network I/O and allows several Notification Service instances to claim disjoint batches. If an instance dies after claiming but before completion, `PROCESSING` becomes eligible again after the lease timeout.

### Provider exactly-once boundary

A crash can occur after an external provider accepts a message but before Notification Service persists `SENT`. Retrying that row can therefore call the provider again. The same stable notification ID is passed as the provider idempotency key on every attempt. A production provider adapter should forward that key to a provider-side idempotency facility when available.

The platform deliberately does **not** claim end-to-end exactly-once notification delivery. The built-in `LoggingNotificationProvider` is a local development adapter and performs no real email/SMS network call.

### Retry and terminal state

Retryable provider failures use exponential backoff bounded by `base-backoff-ms`, `max-backoff-ms`, and `max-attempts`. A non-retryable provider exception goes directly to `FAILED`. Provider failures remain inspectable through the delivery API and are not sent to the Kafka DLT because the source event was already ingested successfully.

### Kafka ingestion DLT

Malformed deterministic input is not retried pointlessly. Other ingestion failures receive bounded Kafka retry before recovery to:

```text
payments.created.v1.notification.DLT
```

This topic is intentionally different from Transaction and Audit DLTs because each consumer has independent responsibilities and recovery semantics.

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

All four services validate configured JWT signature/JWKS, issuer, audience, timing, and a non-empty subject.

## Observability plane

Notification Service adds:

```text
notifications.ingestion.events{outcome=received|stored|duplicate|malformed|dead_lettered}
notifications.delivery.attempts{outcome=sent|retry_scheduled|failed}
```

Prometheus scrapes ports `8080`, `8081`, `8082`, and `8083`. OpenTelemetry export remains opt-in. The Kafka listener trace and later scheduled provider-dispatch trace are separate because trace context is not persisted with the notification row.

## Container-backed verification

CI runs infrastructure configuration validation plus the entire Maven reactor. Notification Service uses real Kafka + PostgreSQL containers to verify:

- duplicate logical payment events create one notification row;
- a transient provider failure persists retry state and later reaches `SENT` on a second attempt;
- a permanent provider failure reaches `FAILED` after one attempt;
- malformed JSON reaches `payments.created.v1.notification.DLT`;
- unauthenticated notification queries return `401`;
- tokens without `notification:read` return `403`;
- the generated OpenAPI contract remains public.

The existing Payment, Transaction, and Audit Testcontainers suites continue to verify their respective correctness boundaries.

## Future expansion

The next useful infrastructure milestone is packaging these service boundaries for deployment: Kubernetes/Helm resources, configuration/secrets boundaries, readiness/liveness behavior, and an AWS deployment design. A real email/SMS provider adapter should remain a swappable edge component rather than leaking vendor APIs into the notification domain.
