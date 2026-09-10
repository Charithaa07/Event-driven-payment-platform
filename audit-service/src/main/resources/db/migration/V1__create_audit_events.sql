CREATE TABLE audit_events (
    event_id UUID PRIMARY KEY,
    event_type VARCHAR(120) NOT NULL,
    aggregate_type VARCHAR(60) NOT NULL,
    aggregate_id UUID NOT NULL,
    customer_id VARCHAR(120) NOT NULL,
    source_topic VARCHAR(160) NOT NULL,
    source_partition INTEGER NOT NULL,
    source_offset BIGINT NOT NULL,
    payload TEXT NOT NULL,
    record_sha256 CHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_audit_source_position UNIQUE (source_topic, source_partition, source_offset)
);

CREATE INDEX idx_audit_events_aggregate_time
    ON audit_events(aggregate_id, occurred_at, recorded_at);

CREATE INDEX idx_audit_events_customer_time
    ON audit_events(customer_id, occurred_at, recorded_at);

CREATE OR REPLACE FUNCTION reject_audit_event_mutation()
RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit_events is append-only';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER audit_events_reject_update_delete
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW EXECUTE FUNCTION reject_audit_event_mutation();
