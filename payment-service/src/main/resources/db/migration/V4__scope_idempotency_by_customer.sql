ALTER TABLE payments
    DROP CONSTRAINT IF EXISTS payments_idempotency_key_key;

ALTER TABLE payments
    ADD CONSTRAINT uq_payments_customer_idempotency
    UNIQUE (customer_id, idempotency_key);
