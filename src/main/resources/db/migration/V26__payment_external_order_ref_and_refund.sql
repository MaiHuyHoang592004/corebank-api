-- ============================================================
-- V26: Payment order enhancements for external integration
--
-- 1. external_order_ref — lets external callers (e.g. RentFlow) trace
--    their own order IDs into corebank without modifying the core schema.
-- 2. refunded_amount_minor — running total of refunds on a payment order.
-- 3. Extend status / event_type CHECK constraints to include refund states.
-- ============================================================

-- 1. Add external_order_ref on payment_orders
ALTER TABLE payment_orders
    ADD COLUMN external_order_ref VARCHAR(128) NULL;

-- Partial index — only rows from external merchants carry this value
CREATE INDEX ix_payment_orders_external_order_ref
    ON payment_orders (external_order_ref)
    WHERE external_order_ref IS NOT NULL;

-- 2. Track cumulative refunded amount on payment_orders
ALTER TABLE payment_orders
    ADD COLUMN refunded_amount_minor BIGINT NOT NULL DEFAULT 0;

ALTER TABLE payment_orders
    ADD CONSTRAINT payment_orders_refunded_amount_non_negative
        CHECK (refunded_amount_minor >= 0);

-- 3. Extend status enum on payment_orders (drop + re-add inline check)
ALTER TABLE payment_orders
    DROP CONSTRAINT IF EXISTS payment_orders_status_check;

ALTER TABLE payment_orders
    ADD CONSTRAINT payment_orders_status_check
        CHECK (status IN (
            'INITIATED', 'AUTHORIZED', 'PARTIALLY_CAPTURED', 'CAPTURED',
            'PARTIALLY_REFUNDED', 'REFUNDED', 'VOIDED', 'FAILED', 'EXPIRED'
        ));

-- 4. Extend event_type enum on payment_events (drop + re-add inline check)
ALTER TABLE payment_events
    DROP CONSTRAINT IF EXISTS payment_events_event_type_check;

ALTER TABLE payment_events
    ADD CONSTRAINT payment_events_event_type_check
        CHECK (event_type IN (
            'INITIATED', 'AUTHORIZED', 'PARTIALLY_CAPTURED', 'CAPTURED',
            'PARTIALLY_REFUNDED', 'REFUNDED', 'VOIDED', 'FAILED', 'EXPIRED'
        ));
