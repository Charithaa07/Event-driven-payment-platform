# Architecture Notes

## Phase 6 request and event flow

1. Client sends `POST /api/v1/payments` with an `Idempotency-Key`.
2. Payment Service checks Redis for a cached payment response associated with that key.
3. On a Redis hit, the cached payment response is returned without running the PostgreSQL idempotency lookup.
4. On a Redis miss or Redis connection failure, Payment Service checks PostgreSQL for the durable `idempotency_key` mapping.
5. If a persisted payment already exists, it is returned and Redis is warmed from the durable record.
6. Otherwise the Payment Service writes both the payment row and a `payments.created.v1` outbox row in the same PostgreSQL transaction.
7. The newly created payment response is cached in Redis only after the database transaction commits.
8. The outbox relay publishes the event to Kafka and marks the outbox record as published after Kafka acknowledges it.
9. Transaction Service consumes the event using the payment ID as the Kafka message key.
10. Transaction Service checks `processed_events` for the immutable event ID.
11. If the event has already been processed, no business write is repeated.
12. Otherwise the transaction row and processed-event marker are committed together in the Transaction Service database.
13. With record-level acknowledgement, the Kafka offset advances only after the listener returns successfully.
14. Retryable failures are attempted two additional times with a 1-second fixed backoff.
15. If processing still fails, the original record is published to `payments.created.v1.DLT`.
16. Malformed payloads are classified as non-retryable and go directly to the DLT after the first failure.

## Payment request idempotency flow

```text
POST /api/v1/payments + Idempotency-Key
        |
        v
Redis response cache
        |
        +-- hit --------------------------> return cached payment
        |
        +-- miss / unavailable
                 |
                 v
          PostgreSQL lookup
                 |
                 +-- existing -----------> warm Redis -> return payment
                 |
                 +-- not found
                        |
                        v
                 BEGIN TRANSACTION
                   +-- INSERT payment
                   +-- INSERT outbox event
                 COMMIT
                        |
                        v
                 cache response in Redis
                        |
                        v
                   return payment
```

Redis is deliberately not the correctness boundary. The cache can be empty, unavailable, or contain an invalid value without changing payment correctness. PostgreSQL remains authoritative through the unique `idempotency_key` constraint.

The Redis value contains the complete payment response required by the duplicate-create path, not only a payment identifier. This lets a cache hit avoid the PostgreSQL idempotency lookup entirely.

New payment responses are written to Redis only after the payment/outbox transaction commits. This prevents an uncommitted or rolled-back payment from becoming visible through the cache.

Cached responses use a configurable TTL. Malformed cache values are evicted and treated as misses.

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
        |      |
        |      +--> retry 1 (1s)
        |      +--> retry 2 (1s)
        |      +--> payments.created.v1.DLT
        |
        +-- malformed payload ------------> payments.created.v1.DLT
```

The dead-letter publisher preserves the failed Kafka record and adds dead-letter metadata headers. Publishing failures are surfaced instead of silently treating the record as recovered.

## Delivery semantics

The platform intentionally uses at-least-once delivery.

The outbox relay may publish the same event again if the process fails after Kafka acknowledges a send but before the outbox row is marked `PUBLISHED`. Likewise, the Transaction Service may receive the same event again around consumer/database failure boundaries.

Those duplicate-delivery windows are handled through durable idempotency:

- Payment API: unique `Idempotency-Key` in PostgreSQL, accelerated by Redis
- Transaction consumer: unique `event_id` in `processed_events`
- Transaction datastore: unique `payment_id` and `source_event_id`

## Service data ownership

Payment Service owns the payment database on local port `5432`.
Transaction Service owns a separate transaction database on local port `5433`.

Neither service reads the other service's tables. Kafka is the service integration boundary.

Redis is shared infrastructure used only as an optimization for Payment Service request idempotency. It does not own durable payment state.

## Retry policy

The first retry policy is intentionally simple and observable:

- Original delivery + 2 retries
- 1 second between attempts
- `IllegalArgumentException` is non-retryable
- Exhausted failures are published to `<original-topic>.DLT`
- DLT send failures are treated as recovery failures

This uses blocking retries on the consumer thread. A future scale-oriented version could move long delays to non-blocking retry topics so partitions are not held during backoff.

## Container-backed integration verification

The integration suite uses Testcontainers to exercise Redis, PostgreSQL, and Kafka with real service dependencies during CI.

### Redis idempotency verification

A real Redis container verifies that the Payment Service idempotency store serializes the complete payment response, restores it correctly, and applies a TTL. The test also injects a malformed cached value and verifies that it is evicted and treated as a cache miss.

A separate unit test forces Redis connection failures and verifies fail-open behavior, ensuring payment processing can continue through PostgreSQL when the cache is unavailable.

### Kafka/PostgreSQL verification

The Transaction Service integration suite boots a real PostgreSQL 17 instance and an Apache Kafka broker.

The database starts with an empty schema. Flyway migrations run before Hibernate validation, proving the service can bootstrap its datastore from migrations alone.

The integration tests verify two reliability paths:

1. The same `payments.created.v1` event is published twice. The consumer processes both deliveries but persists exactly one business transaction and one durable `processed_events` marker.
2. A malformed JSON event is published to `payments.created.v1`. The consumer classifies the deserialization failure as non-retryable and the original record is observed on `payments.created.v1.DLT`.

Together these tests validate cache behavior, event wiring, schema migration, durable idempotency, and dead-letter publication using production-like infrastructure boundaries.

## Next reliability milestones

The next iterations will add operational DLT replay, richer observability around retry counts/dead-letter volume/consumer lag, concurrent-request idempotency race handling, and multi-instance outbox claiming with `SKIP LOCKED`.
