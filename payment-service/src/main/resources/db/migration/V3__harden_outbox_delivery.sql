ALTER TABLE outbox_events
    ADD COLUMN next_attempt_at TIMESTAMPTZ,
    ADD COLUMN claimed_at TIMESTAMPTZ,
    ADD COLUMN last_error VARCHAR(1000);

UPDATE outbox_events
SET next_attempt_at = created_at
WHERE next_attempt_at IS NULL;

ALTER TABLE outbox_events
    ALTER COLUMN next_attempt_at SET NOT NULL;

DROP INDEX IF EXISTS idx_outbox_events_status_created_at;

CREATE INDEX idx_outbox_events_delivery
    ON outbox_events(status, next_attempt_at, claimed_at, created_at);
