# Architecture Notes

## Phase 6.1 request and event flow

1. Client sends `POST /api/v1/payments` with an `Idempotency-Key`.
2. Payment Service checks Redis for a cached response associated with the key.
3. On a cache hit, the cached payment is compared with the incoming request. Matching data returns the original payment; different data raises an idempotency conflict.
4. On a Redis miss or Redis failure, Payment Service checks PostgreSQL for the durable key mapping and applies the same request comparison.
5. If the key is absent, Payment Service attempts `INSERT ... ON CONFLICT DO NOTHING` inside the payment transaction.
6. A successful insert writes the payment and its `payments.created.v1` outbox event in the same transaction.
7. If another request won the insert race, the losing request loads the winning payment. Matching request data returns the winner; different data returns `409 Conflict`.
8. The newly created response is cached in Redis only after the payment/outbox transaction commits.
9. An outbox relay claims eligible rows in a short PostgreSQL transaction using `FOR UPDATE SKIP LOCKED`, marks them `PROCESSING`, records `claimed_at`, and commits the claim.
10. Kafka publication happens after that claim transaction has ended.
11. A successful send marks the event `PUBLISHED`. A failed send records diagnostics and schedules a later attempt using bounded backoff. Events that reach the maximum attempt count become `FAILED`.
12. A stale `PROCESSING` lease becomes claimable again so an instance crash cannot strand the event permanently.
13. Transaction Service consumes `payments.created.v1`, checks `processed_events`, and writes its transaction row and processed-event marker together.
14. Kafka offsets advance after successful local processing.
15. Retryable consumer failures receive two retries with a one-second fixed backoff; exhausted failures go to `payments.created.v1.DLT`.
16. Malformed payloads are non-retryable and go directly to the DLT.

## Payment request idempotency

```text
POST /api/v1/payments + Idempotency-Key
        |
        v
Redis response cache
        |
        +-- hit --> compare request
        |              +-- same ---------> return original payment
        |              `-- different ----> 409 Conflict
        |
        `-- miss / unavailable
                 |
                 v
          PostgreSQL lookup
                 |
                 +-- existing --> compare request --> warm Redis / 409
                 |
                 `-- absent
                        |
                        v
             INSERT ... ON CONFLICT DO NOTHING
                 |
                 +-- won race
                 |     +-- INSERT payment
                 |     +-- INSERT outbox event
                 |     `-- COMMIT -> cache response
                 |
                 `-- lost race
                       `-- load winner -> compare request -> return / 409
```

PostgreSQL is the correctness boundary. Redis can be absent or unavailable without changing the API's durable idempotency guarantee. The database insert is deliberately conflict-tolerant so simultaneous retries do not surface a uniqueness exception to one caller.

The key is bound to the original payment semantics. The service compares amount, currency, and customer before returning an existing payment. Reusing the same key with different payment data is treated as a conflict rather than silently returning unrelated state.

## Transactional outbox and multi-instance claiming

```text
payment transaction
  +-- payment row
  `-- PENDING outbox row
          |
          v
relay claim transaction
  SELECT eligible rows
  FOR UPDATE SKIP LOCKED
  mark PROCESSING
  set claimed_at
  increment attempts
COMMIT
          |
          v
Kafka send outside DB transaction
    |
    +-- success --> PUBLISHED
    |
    `-- failure --> PENDING + next_attempt_at + last_error
                         |
                         `-- attempts exhausted --> FAILED

stale PROCESSING lease (>30s) --> eligible for reclaim
```

`SKIP LOCKED` lets multiple Payment Service instances poll concurrently without waiting on or claiming the same rows in the same pass. The processing lease handles crashes after claiming. Kafka I/O is intentionally outside the claim transaction so a slow broker does not keep row locks and a database transaction open for the duration of a network request.

This design still provides at-least-once delivery, not cross-system exactly-once delivery. A process can die after Kafka accepts an event but before PostgreSQL records `PUBLISHED`, causing the event to be sent again. Downstream idempotency is therefore a required part of the design.

## Consumer failure flow

```text
payments.created.v1
        |
        v
Transaction Service
        |
        +-- success ----------------------> commit offset
        |
        +-- retryable failure
        |      +--> retry 1 (1s)
        |      +--> retry 2 (1s)
        |      `--> payments.created.v1.DLT
        |
        `-- malformed payload ------------> payments.created.v1.DLT
```

The Transaction Service stores the immutable event ID in `processed_events`. Its business transaction row and processed-event marker are committed together. Database-level uniqueness on payment/source-event IDs adds another guard against repeated writes.

## Service data ownership

Payment Service owns the payment PostgreSQL database on local port `5432`.
Transaction Service owns a separate PostgreSQL database on local port `5433`.

Neither service reads the other service's tables. Kafka is the service integration boundary. Redis is shared infrastructure used only to accelerate Payment Service idempotency responses and does not own durable payment state.

## Retry policies

### Consumer

- Original delivery + 2 retries
- 1 second between attempts
- `IllegalArgumentException` is non-retryable
- Exhausted failures are published to `<original-topic>.DLT`
- DLT send failures are surfaced

### Outbox relay

- Claiming increments the delivery attempt count
- Failed deliveries receive bounded exponential backoff
- Default maximum attempts: 10
- Default backoff starts at 1 second and is capped at 60 seconds
- Final exhausted state: `FAILED`
- `PROCESSING` rows older than the lease window are eligible for reclaim

The consumer currently uses blocking retries. A future scale-oriented iteration could use non-blocking retry topics for long delays.

## Container-backed verification

The Maven integration suite exercises real Redis, PostgreSQL, and Kafka dependencies through Testcontainers.

### Payment Service

A PostgreSQL + Redis suite verifies:

- two simultaneous same-key create requests resolve to one payment;
- only one outbox event is created for the winning insert;
- same-key/different-body requests are rejected;
- rolled-back payment/outbox work never populates Redis;
- committed responses are cached;
- Flyway can bootstrap the payment schema, including the hardened outbox migration;
- the `FOR UPDATE SKIP LOCKED` claim query executes successfully against real PostgreSQL.

A separate Redis test verifies TTL behavior and malformed-cache eviction. Unit coverage forces a Redis connection failure to prove the fast path remains fail-open.

### Transaction Service

The Kafka/PostgreSQL suite uses the regular `apache/kafka:4.0.0` image and verifies:

1. Publishing the same `payments.created.v1` event twice produces exactly one business transaction and one durable processed-event marker.
2. Publishing malformed JSON causes the original record to appear on `payments.created.v1.DLT`.

The regular Kafka image is intentionally used instead of the native image after a native-container crash was observed on a hosted CI runner.

## Remaining reliability/operations work

The core reliability mechanisms through Phase 6.1 are implemented. Future iterations can add operational DLT replay, metrics and traces for outbox age/retry count/DLT volume/consumer lag, stronger relay ownership identifiers and configurable lease duration, and production deployment controls such as Kubernetes probes and AWS infrastructure.
