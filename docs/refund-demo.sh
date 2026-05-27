#!/usr/bin/env bash
# Smoke-test for PR A — runs a full authorize → capture → partial-refund → full-refund cycle.
# Requires a running corebank-api on localhost:8080 with demo seed data loaded.
# Usage: bash docs/refund-demo.sh
set -euo pipefail

BASE="http://localhost:8080"
AUTH_HEADER='-u demo_user:demo_user'
HDR='-H "Content-Type: application/json"'

echo "=== corebank PR A smoke demo ==="

# ---- 1. Authorize hold with external_order_ref ----
echo -e "\n[1] POST /api/payments/authorize-hold"
AUTH_RESP=$(curl -s $AUTH_HEADER -X POST "$BASE/api/payments/authorize-hold" \
  -H "Content-Type: application/json" \
  -d '{
    "idempotencyKey": "demo-refund-auth-1",
    "payerAccountId": "20000000-0000-0000-0000-000000000001",
    "payeeAccountId": "20000000-0000-0000-0000-000000000002",
    "amountMinor": 100000,
    "currency": "VND",
    "paymentType": "MERCHANT_PAYMENT",
    "description": "Demo refund flow",
    "externalOrderRef": "demo:1",
    "actor": "demo_user",
    "correlationId": "00000000-0000-0000-0000-000000000001",
    "requestId": "00000000-0000-0000-0000-000000000002",
    "sessionId": "00000000-0000-0000-0000-000000000003",
    "traceId": "trace-demo-refund-1"
  }')
echo "$AUTH_RESP" | jq .
PAYMENT_ORDER_ID=$(echo "$AUTH_RESP" | jq -r '.paymentOrderId')
HOLD_ID=$(echo "$AUTH_RESP" | jq -r '.holdId')
echo "paymentOrderId=$PAYMENT_ORDER_ID  holdId=$HOLD_ID"

# ---- 2. Capture hold ----
echo -e "\n[2] POST /api/payments/capture-hold"
curl -s $AUTH_HEADER -X POST "$BASE/api/payments/capture-hold" \
  -H "Content-Type: application/json" \
  -d "{
    \"idempotencyKey\": \"demo-refund-cap-1\",
    \"holdId\": \"$HOLD_ID\",
    \"amountMinor\": 100000,
    \"debitLedgerAccountId\": \"10000000-0000-0000-0000-000000000001\",
    \"creditLedgerAccountId\": \"10000000-0000-0000-0000-000000000002\",
    \"actor\": \"demo_user\",
    \"correlationId\": \"00000000-0000-0000-0000-000000000004\",
    \"requestId\": \"00000000-0000-0000-0000-000000000005\",
    \"traceId\": \"trace-demo-refund-2\"
  }" | jq .

# ---- 3. Query by externalOrderRef (status CAPTURED) ----
echo -e "\n[3] GET /api/payments/orders?externalOrderRef=demo:1"
curl -s $AUTH_HEADER "$BASE/api/payments/orders?externalOrderRef=demo:1" | jq '.items[0].status'

# ---- 4. Partial refund ----
echo -e "\n[4] POST /api/payments/refund (partial: 30000)"
curl -s $AUTH_HEADER -X POST "$BASE/api/payments/refund" \
  -H "Content-Type: application/json" \
  -d "{
    \"idempotencyKey\": \"demo-refund-partial-1\",
    \"paymentOrderId\": \"$PAYMENT_ORDER_ID\",
    \"amountMinor\": 30000,
    \"actor\": \"demo_user\",
    \"correlationId\": \"00000000-0000-0000-0000-000000000006\",
    \"requestId\": \"00000000-0000-0000-0000-000000000007\",
    \"traceId\": \"trace-demo-refund-3\",
    \"description\": \"partial refund\"
  }" | jq '{paymentStatus, refundedAmountMinor, cumulativeRefundedMinor}'

# ---- 5. Query by id — PARTIALLY_REFUNDED ----
echo -e "\n[5] GET /api/payments/orders/$PAYMENT_ORDER_ID"
curl -s $AUTH_HEADER "$BASE/api/payments/orders/$PAYMENT_ORDER_ID" | jq '{status, refundedAmountMinor}'

# ---- 6. Full refund ----
echo -e "\n[6] POST /api/payments/refund (remaining: 70000)"
curl -s $AUTH_HEADER -X POST "$BASE/api/payments/refund" \
  -H "Content-Type: application/json" \
  -d "{
    \"idempotencyKey\": \"demo-refund-full-1\",
    \"paymentOrderId\": \"$PAYMENT_ORDER_ID\",
    \"amountMinor\": 70000,
    \"actor\": \"demo_user\",
    \"correlationId\": \"00000000-0000-0000-0000-000000000008\",
    \"requestId\": \"00000000-0000-0000-0000-000000000009\",
    \"traceId\": \"trace-demo-refund-4\",
    \"description\": \"full refund\"
  }" | jq '{paymentStatus, cumulativeRefundedMinor}'

# ---- 7. Final state — REFUNDED ----
echo -e "\n[7] GET /api/payments/orders/$PAYMENT_ORDER_ID (expect REFUNDED)"
curl -s $AUTH_HEADER "$BASE/api/payments/orders/$PAYMENT_ORDER_ID" | jq '{status, refundedAmountMinor}'

# ---- 8. Over-refund attempt — expect 400 ----
echo -e "\n[8] POST /api/payments/refund (amount=1, should return 400)"
STATUS=$(curl -s $AUTH_HEADER -o /dev/null -w "%{http_code}" -X POST "$BASE/api/payments/refund" \
  -H "Content-Type: application/json" \
  -d "{
    \"idempotencyKey\": \"demo-refund-over-1\",
    \"paymentOrderId\": \"$PAYMENT_ORDER_ID\",
    \"amountMinor\": 1,
    \"actor\": \"demo_user\",
    \"correlationId\": \"00000000-0000-0000-0000-000000000010\",
    \"requestId\": \"00000000-0000-0000-0000-000000000011\",
    \"traceId\": \"trace-demo-refund-5\"
  }")
echo "HTTP status: $STATUS (expected 400 — order is REFUNDED)"

echo -e "\n=== Demo complete ==="
