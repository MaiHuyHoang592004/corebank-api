-- Outbox Pattern Implementation
-- This migration creates the outbox table for reliable event publishing

-- Create outbox table for event publishing
CREATE TABLE outbox_events (
    id BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_data JSONB NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP WITH TIME ZONE,
    status VARCHAR(20) DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PROCESSING', 'PROCESSED', 'FAILED')),
    retry_count INTEGER DEFAULT 0,
    last_error TEXT,
    correlation_id VARCHAR(100),
    causation_id VARCHAR(100)
);

-- Create index for efficient processing
CREATE INDEX idx_outbox_events_pending ON outbox_events (status, created_at) WHERE status = 'PENDING';

-- Create function to insert outbox events
CREATE OR REPLACE FUNCTION insert_outbox_event(
    p_aggregate_type VARCHAR(100),
    p_aggregate_id VARCHAR(100),
    p_event_type VARCHAR(100),
    p_event_data JSONB,
    p_correlation_id VARCHAR(100) DEFAULT NULL,
    p_causation_id VARCHAR(100) DEFAULT NULL
) RETURNS BIGINT AS $$
DECLARE
    v_event_id BIGINT;
BEGIN
    INSERT INTO outbox_events (
        aggregate_type,
        aggregate_id,
        event_type,
        event_data,
        correlation_id,
        causation_id
    ) VALUES (
        p_aggregate_type,
        p_aggregate_id,
        p_event_type,
        p_event_data,
        p_correlation_id,
        p_causation_id
    ) RETURNING id INTO v_event_id;
    
    RETURN v_event_id;
END;
$$ LANGUAGE plpgsql;

-- Create function to mark events as processed
CREATE OR REPLACE FUNCTION mark_outbox_event_processed(
    p_event_id BIGINT,
    p_status VARCHAR(20) DEFAULT 'PROCESSED'
) RETURNS BOOLEAN AS $$
BEGIN
    UPDATE outbox_events 
    SET 
        processed_at = CURRENT_TIMESTAMP,
        status = p_status
    WHERE id = p_event_id AND status = 'PENDING';
    
    RETURN FOUND;
END;
$$ LANGUAGE plpgsql;

-- Create function to mark events as failed
CREATE OR REPLACE FUNCTION mark_outbox_event_failed(
    p_event_id BIGINT,
    p_error TEXT
) RETURNS BOOLEAN AS $$
BEGIN
    UPDATE outbox_events 
    SET 
        processed_at = CURRENT_TIMESTAMP,
        status = 'FAILED',
        retry_count = retry_count + 1,
        last_error = p_error
    WHERE id = p_event_id;
    
    RETURN FOUND;
END;
$$ LANGUAGE plpgsql;

-- Create function to get pending events for processing
CREATE OR REPLACE FUNCTION get_pending_outbox_events(
    p_limit INTEGER DEFAULT 100,
    p_batch_size INTEGER DEFAULT 10
) RETURNS SETOF outbox_events AS $$
DECLARE
    v_event_ids BIGINT[];
    v_event_id BIGINT;
BEGIN
    -- Get event IDs to process
    SELECT array_agg(id) INTO v_event_ids
    FROM (
        SELECT id 
        FROM outbox_events 
        WHERE status = 'PENDING'
        ORDER BY created_at ASC
        LIMIT p_limit
    ) pending_events;
    
    IF v_event_ids IS NOT NULL THEN
        -- Mark events as processing in batches
        FOREACH v_event_id IN ARRAY v_event_ids
        LOOP
            UPDATE outbox_events 
            SET status = 'PROCESSING'
            WHERE id = v_event_id AND status = 'PENDING';
            
            -- Return the event
            RETURN QUERY SELECT * FROM outbox_events WHERE id = v_event_id;
        END LOOP;
    END IF;
END;
$$ LANGUAGE plpgsql;

-- Insert sample outbox events for testing
INSERT INTO outbox_events (
    aggregate_type,
    aggregate_id,
    event_type,
    event_data,
    correlation_id,
    causation_id
) VALUES 
(
    'CustomerAccount',
    'a1',
    'AccountOpened',
    '{"accountId": "a1", "customerId": "c1", "accountType": "CURRENT", "currency": "VND", "initialBalance": 0}',
    'corr-001',
    NULL
),
(
    'Transfer',
    'trf-001',
    'TransferInitiated',
    '{"transferId": "trf-001", "sourceAccountId": "a1", "targetAccountId": "a2", "amount": 1000000, "currency": "VND"}',
    'corr-002',
    NULL
),
(
    'Payment',
    'pmt-001',
    'PaymentAuthorized',
    '{"paymentId": "pmt-001", "accountId": "a1", "amount": 500000, "currency": "VND", "holdId": "hold-001"}',
    'corr-003',
    NULL
);

COMMENT ON TABLE outbox_events IS 'Outbox pattern table for reliable event publishing to Kafka';
COMMENT ON COLUMN outbox_events.aggregate_type IS 'Type of aggregate that generated the event';
COMMENT ON COLUMN outbox_events.aggregate_id IS 'ID of the aggregate that generated the event';
COMMENT ON COLUMN outbox_events.event_type IS 'Type of domain event';
COMMENT ON COLUMN outbox_events.event_data IS 'JSON payload of the domain event';
COMMENT ON COLUMN outbox_events.status IS 'Processing status: PENDING, PROCESSING, PROCESSED, FAILED';
COMMENT ON COLUMN outbox_events.retry_count IS 'Number of processing attempts';
COMMENT ON COLUMN outbox_events.correlation_id IS 'ID for tracing related events';
COMMENT ON COLUMN outbox_events.causation_id IS 'ID of the event that caused this event';