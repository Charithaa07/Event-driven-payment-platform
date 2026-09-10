CREATE TABLE dead_letter_events (
    id UUID PRIMARY KEY,
    source_topic VARCHAR(160) NOT NULL,
    dlt_topic VARCHAR(160) NOT NULL,
    dlt_partition INTEGER NOT NULL,
    dlt_offset BIGINT NOT NULL,
    message_key VARCHAR(255),
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    received_at TIMESTAMPTZ NOT NULL,
    replay_claimed_at TIMESTAMPTZ,
    replayed_at TIMESTAMPTZ,
    replay_attempts INTEGER NOT NULL DEFAULT 0,
    replayed_by VARCHAR(120),
    last_replay_error VARCHAR(1000),
    CONSTRAINT uq_dead_letter_position UNIQUE (dlt_topic, dlt_partition, dlt_offset)
);

CREATE INDEX idx_dead_letter_events_status_received_at
    ON dead_letter_events(status, received_at DESC);
