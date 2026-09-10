# Architecture Notes

## Phase 1 request flow

1. Client sends `POST /api/v1/payments` with an `Idempotency-Key`.
2. Payment Service checks whether that key already maps to a persisted payment.
3. If it exists, the original payment is returned.
4. Otherwise a new payment is persisted in PostgreSQL.
5. A `payments.created.v1` event is published to Kafka using the payment ID as its message key.

## Known Phase 1 trade-off

Database commit + Kafka publish is currently a dual write. A process failure between those operations can leave a committed payment without a corresponding event. Phase 2 introduces a transactional outbox table written in the same database transaction and a publisher that asynchronously relays outbox records to Kafka.
