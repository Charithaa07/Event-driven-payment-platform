# Architecture Notes

## Phase 3 request and event flow

1. Client sends `POST /api/v1/payments` with an `Idempotency-Key`.
2. Payment Service checks whether that key already maps to a persisted payment.
3. If it exists, the original payment is returned.
4. Otherwise the Payment Service writes both the payment row and a `payments.created.v1` outbox row in the same PostgreSQL transaction.
5. The outbox relay publishes the event to Kafka and marks the outbox record as published after Kafka acknowledges it.
6. Transaction Service consumes the event using the payment ID as the Kafka message key.
7. Transaction Service checks `processed_events` for the immutable event ID.
8. If the event has already been processed, no business write is repeated.
9. Otherwise the transaction row and processed-event marker are committed together in the Transaction Service database.
10. Kafka is acknowledged only after the local database transaction completes.

## Delivery semantics

The platform intentionally uses at-least-once delivery.

The outbox relay may publish the same event again if the process fails after Kafka acknowledges a send but before the outbox row is marked `PUBLISHED`. Likewise, the Transaction Service may receive the same event again if its database transaction commits but the process fails before the Kafka offset is acknowledged.

Those duplicate-delivery windows are handled through durable idempotency:

- Payment API: unique `Idempotency-Key`
- Transaction consumer: unique `event_id` in `processed_events`
- Transaction datastore: unique `payment_id` and `source_event_id`

## Service data ownership

Payment Service owns the payment database on local port `5432`.
Transaction Service owns a separate transaction database on local port `5433`.

Neither service reads the other service's tables. Kafka is the service integration boundary.

## Next reliability milestone

Phase 4 will add retry handling and a dead-letter topic for messages that cannot be processed successfully after bounded retries. This will separate transient failures from poison messages and make failure recovery observable instead of relying on endless consumer retries.
