# 14. Source of Truth Map

Tài liệu này trả lời câu hỏi:
- dữ liệu nào là **authoritative** cho từng mối quan tâm
- dữ liệu nào chỉ là **projection / cache / operational copy**
- khi có mâu thuẫn thì **tin cái gì trước**

---

## 1. Nguyên tắc chung

Trong dự án này không có một “single source of truth” cho mọi thứ.
Thay vào đó, mỗi concern có một **authoritative store** riêng.

Quy tắc quan trọng nhất:

> **Financial truth luôn nằm ở PostgreSQL ledger/accounting model.**

Các hệ khác như Kafka, Redis, read model, dashboard table chỉ là:
- transport
- acceleration
- projection
- coordination

Chúng không được quyết định số dư thật.

---

## 2. Bản đồ source of truth theo concern

## 2.1 Financial truth

### Current implementation note
- Hiện tại source table cho current balance là `account.customer_accounts`
- Tên `account.account_balances_current` trong tài liệu mang tính target/logical naming cho giai đoạn refactor sau

### Authoritative
- `ledger.ledger_journals`
- `ledger.ledger_postings`
- `account.customer_accounts`
- `account.account_balances_current`
- `payment.funds_holds`

### Not authoritative
- `reporting.account_read_models`
- Redis balance cache
- Kafka payment/account topics
- UI DTOs

### Rule
- Nếu UI hiển thị balance khác DB, tin DB
- Nếu projector/update read model bị trễ, không dùng read model để authorize tiền
- Nếu Kafka event mất/delay, không làm thay đổi financial truth đã commit

---

## 2.2 Idempotency truth

### Authoritative
- `integration.idempotency_keys`

### Not authoritative
- Redis dedupe cache ngắn hạn
- client retry token trong memory

### Rule
- Chỉ `integration.idempotency_keys` mới được quyết định request money-moving đã xử lý hay chưa
- Redis chỉ hỗ trợ chống burst hoặc giảm tải

---

## 2.3 Customer identity truth

### Authoritative
- `customer.customers`
- `customer.customer_documents`
- `customer.risk_profiles`
- `customer.encrypted_customer_secrets`

### Not authoritative
- cached profile JSON
- search index
- dashboard read model

---

## 2.4 Product & pricing truth

### Authoritative
- `product.bank_products`
- `product.bank_product_versions`
- `product.posting_rule_sets`
- `product.posting_rule_lines`

### Rule
- Hợp đồng cũ phải trỏ vào **version cũ**
- Không overwrite trực tiếp config đang được historical contracts sử dụng

---

## 2.5 Approval truth

### Authoritative
- `ops.approvals`

### Rule
- Quyết định pending/approved/rejected phải lấy từ approval table
- Không tin trạng thái approval chỉ có trong Kafka event hoặc UI local state

---

## 2.6 Saga / orchestration truth

### Authoritative
- `integration.saga_instances`
- `integration.saga_steps`

### Rule
- Nếu một flow distributed bị lỗi giữa chừng, hệ thống nhìn vào saga tables để biết đang ở bước nào
- Không được suy diễn trạng thái saga chỉ từ event log rời rạc

---

## 2.7 Async integration truth

### Authoritative cho “event cần publish hay chưa”
- `integration.outbox_messages`

### Not authoritative
- Kafka broker state
- consumer offsets riêng lẻ

### Rule
- Event chỉ được coi là “cần publish” nếu tồn tại trong outbox sau khi DB transaction commit
- Không publish trực tiếp từ business code trước commit

---

## 2.8 Query/UI truth

### Authoritative cho UI nhanh
- `reporting.account_read_models`
- materialized views / snapshots

### Nhưng không authoritative cho financial decisions
- read model chỉ là **query truth**, không phải **financial truth**

### Rule
- UI timeline/dashboard có thể đọc read model
- authorize/capture/repay/post journal phải đọc source tables chính

---

## 2.9 Runtime mode truth

### Authoritative
- `iam.system_configs`

### Rule
- Chế độ `RUNNING`, `MAINTENANCE`, `EOD_LOCK`, `READ_ONLY` phải lấy từ DB/system config authoritative
- Không rely vào env var in-memory nếu system mode cần đổi runtime

---

## 2.10 Reconciliation truth

### Current implementation note
- `ops.reconciliation_breaks` đã có nền tảng schema
- `ops.reconciliation_runs` đang là target-state, chưa phải contract vật lý hiện tại

### Authoritative
- `ops.reconciliation_runs`
- `ops.reconciliation_breaks`
- đối chiếu với ledger + external settlement statements

### Rule
- Reconciliation report là derived result
- Ledger vẫn là truth; reconciliation chỉ phát hiện mismatch

---

## 3. Conflict resolution order

Nếu có mâu thuẫn dữ liệu, ưu tiên theo thứ tự này:

### Với tiền/số dư
1. `ledger_journals` + `ledger_postings`
2. `account_balances_current`
3. `funds_holds`
4. snapshots/read models
5. cache/UI

### Với request retry/idempotency
1. `idempotency_keys`
2. audit trail
3. outbox/history
4. Redis/local memory

### Với workflow async
1. DB business state
2. saga tables
3. outbox state
4. Kafka topic/consumer observation
5. logs

---

## 4. Practical guidance for agents

AI agent khi sửa code phải luôn tự hỏi:

- concern này đang hỏi **truth của cái gì**?
- bảng nào là authoritative?
- dữ liệu mình đang đọc có phải projection không?
- thay đổi này có vô tình biến cache/projection thành source of truth không?

---

## 5. Anti-patterns bị cấm

- dùng Redis làm balance source of truth
- dùng Kafka event stream để quyết định posted balance hiện tại
- dùng UI read model để authorize payment
- update read model nhưng quên post ledger
- publish Kafka trực tiếp trước khi DB commit
- overwrite product config version cũ rồi áp logic mới cho contract cũ

---

## 6. Short form

- **PostgreSQL ledger = financial truth**
- **Outbox = publish truth**
- **Saga tables = orchestration truth**
- **Read models = query truth**
- **Redis = acceleration only**
- **Kafka = transport only**
