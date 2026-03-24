-- Non-authoritative notification inbox projection fed from event envelopes.

CREATE TABLE read_model_notification_inbox (
    event_id UUID PRIMARY KEY,
    source_topic VARCHAR(100) NOT NULL,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    correlation_id VARCHAR(100),
    request_id VARCHAR(100),
    actor VARCHAR(100),
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'UNREAD',
    projected_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_notification_inbox_occurred_at
    ON read_model_notification_inbox (occurred_at DESC);

CREATE INDEX idx_notification_inbox_status_occurred
    ON read_model_notification_inbox (status, occurred_at DESC);

COMMENT ON TABLE read_model_notification_inbox IS 'Non-authoritative inbox projection for event notification fanout';
COMMENT ON COLUMN read_model_notification_inbox.payload IS 'Event payload copy from envelope; not a source of truth for balances';
