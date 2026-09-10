# Architecture Notes

## Phase 4 request and event flow

1. Client sends `POST /api/v1/payments` with an `Idempotency-Key`.
2. Payment Service checks whether that key already maps to a persisted payment.
3. If it exists, the original payment is returned.
4. Otherwise the Payment Service writes both the payment row and a `payments.created.v1` outbox row in the same PostgreSQL transaction.
5. The outbox relay publishes the event to Kafka and marks the outbox record as published after Kafka acknowledges it.
6. Transaction Service consumes the event using the payment ID as the Kafka message key.
7. Transaction Service checks `processed_events` for the immutable event ID.
8. If the event has already been processed, no business write is repeated.
9. Otherwise the transaction row and processed-event marker are committed together in the Transaction Service database.
10. With record-level acknowledgement, the Kafka offset advances only after the listener returns successfully.
11. Retryable failures are attempted two additional times with a 1-second fixed backoff.
12. If processing still fails, the original record is published to `payments.created.v1.DLT`.
13. Malformed payloads are classified as non-retryable and go directly to the DLT after the first failure.

## Failure flow

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

- Payment API: unique `Idempotency-Key`
- Transaction consumer: unique `event_id` in `processed_events`
- Transaction datastore: unique `payment_id` and `source_event_id`

## Service data ownership

Payment Service owns the payment database on local port `5432`.
Transaction Service owns a separate transaction database on local port `5433`.

Neither service reads the other service's tables. Kafka is the service integration boundary.

## Retry policy

The first retry policy is intentionally simple and observable:

- Original delivery + 2 retries
- 1 second between attempts
- `IllegalArgumentException` is non-retryable
- Exhausted failures are published to `<original-topic>.DLT`
- DLT send failures are treated as recovery failures

This uses blocking retries on the consumer thread. A future scale-oriented version could move long delays to non-blocking retry topics so partitions are not held during backoff.

## Next reliability milestones

The next iterations will add Testcontainers integration tests for Kafka/PostgreSQL failure paths, operational DLT replay, and richer observability around retry counts, dead-letter volume, and consumer lag.
