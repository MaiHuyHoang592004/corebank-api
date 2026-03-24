-- Minimal non-authoritative read-model event feed for dashboard and replay proofs.

CREATE TABLE read_model_event_feed (
    event_id UUID PRIMARY KEY,
    source_topic VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    schema_version VARCHAR(20) NOT NULL,
    correlation_id VARCHAR(100),
    request_id VARCHAR(100),
    actor VARCHAR(100),
    payload JSONB NOT NULL,
    projected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_read_model_event_feed_aggregate
    ON read_model_event_feed (aggregate_type, aggregate_id, occurred_at DESC);

CREATE INDEX idx_read_model_event_feed_occurred_at
    ON read_model_event_feed (occurred_at DESC);

COMMENT ON TABLE read_model_event_feed IS 'Non-authoritative read model built from replayable event envelopes';
COMMENT ON COLUMN read_model_event_feed.payload IS 'Event payload copied from the envelope; not a source of truth for balances';
