CREATE TABLE notification_deliveries (
    id UUID PRIMARY KEY,
    source_event_id UUID NOT NULL,
    payment_id UUID NOT NULL,
    customer_id VARCHAR(120) NOT NULL,
    channel VARCHAR(20) NOT NULL,
    template VARCHAR(80) NOT NULL,
    amount NUMERIC(19,2) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    status VARCHAR(30) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    processing_started_at TIMESTAMPTZ,
    provider VARCHAR(80),
    provider_message_id VARCHAR(160),
    last_error VARCHAR(500),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_notification_source_channel UNIQUE (source_event_id, channel),
    CONSTRAINT ck_notification_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT ck_notification_status CHECK (
        status IN ('PENDING', 'PROCESSING', 'RETRY_PENDING', 'SENT', 'FAILED')
    )
);

CREATE INDEX idx_notification_payment_created
    ON notification_deliveries(payment_id, created_at);

CREATE INDEX idx_notification_dispatch_due
    ON notification_deliveries(status, next_attempt_at, created_at);

CREATE INDEX idx_notification_processing_lease
    ON notification_deliveries(status, processing_started_at)
    WHERE status = 'PROCESSING';
