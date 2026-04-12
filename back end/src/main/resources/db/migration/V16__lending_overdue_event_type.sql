-- Expand loan event type constraint to support overdue transition events.
ALTER TABLE loan_events
DROP CONSTRAINT loan_events_event_type_check;

ALTER TABLE loan_events
ADD CONSTRAINT loan_events_event_type_check
CHECK (event_type IN ('DISBURSED', 'REPAYMENT', 'CLOSED', 'DEFAULTED', 'OVERDUE'));
