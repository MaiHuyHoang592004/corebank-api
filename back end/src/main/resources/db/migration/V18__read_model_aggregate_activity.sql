-- Typed dashboard summary derived from the non-authoritative event feed.

CREATE TABLE read_model_aggregate_activity (
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    latest_event_id UUID NOT NULL,
    latest_event_type VARCHAR(100) NOT NULL,
    latest_occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_actor VARCHAR(100),
    last_correlation_id VARCHAR(100),
    event_count BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (aggregate_type, aggregate_id)
);

CREATE INDEX idx_read_model_aggregate_activity_latest
    ON read_model_aggregate_activity (latest_occurred_at DESC);

COMMENT ON TABLE read_model_aggregate_activity IS 'Typed dashboard summary derived from read_model_event_feed; non-authoritative';
