# Agent Review Map — corebank-api

Sinh ra từ một lượt review sâu (4 sub-agent song song, mỗi agent đọc trực tiếp
source + đối chiếu với `07-financial-invariants.md`, `19-runtime-failure-modes.md`,
`18-testing-strategy.md`, `11-ops-reliability-security.md`, sau đó được
cross-verify thủ công). Mục đích file này: agent/reviewer sau không cần quét lại
toàn bộ 100 file Java để định vị module hay tái tạo finding — chỉ cần nhảy thẳng
tới `file:line` bên dưới và xác nhận.

**Ngày review:** 2026-09-17 · **Commit tại thời điểm review:** `842e76b`
(branch `claude/magical-volta-2rueqx`) · **Build:** `mvn compile` PASS.

Quy ước trạng thái: `VERIFIED` = đã đọc trực tiếp source xác nhận đúng như mô tả.
`AGENT-REPORTED` = một sub-agent báo cáo, chưa được người/agent thứ hai đọc lại.
Khi cross-check, đổi `AGENT-REPORTED` → `VERIFIED` hoặc `REFUTED` kèm ghi chú.

---

## 1. Bản đồ kiến trúc (package → trách nhiệm → file chính)

| Package | Trách nhiệm | File chính |
|---|---|---|
| `account/` | Đọc/khoá số dư tài khoản khách hàng | `AccountBalanceRepository.java` (khoá deterministic, optimistic version), `BalanceQueryService.java`, `CustomerAccount.java` |
| `customer/` | Hồ sơ khách hàng (không chứa PII nhạy cảm — xem `ops/security`) | `Customer.java`, `CustomerRepository.java` |
| `ledger/` | Sổ cái kép — nơi DUY NHẤT ghi journal/posting | `LedgerCommandService.java` (postJournal, validateBalancedJournal, hash-chain), `LedgerJournal.java`, `LedgerPosting.java`, `HotAccountSlotRuntimeService.java` (slot khoá deterministic cho hot account) |
| `payment/` (+`api/`) | Hold → Capture → Void → Refund | `HoldService.java` (authorize/capture/void/refund), `PaymentApplicationService.java`, `PaymentQueryService.java`, `PaymentRetryPolicy.java`, `api/PaymentController.java` |
| `transfer/` (+`api/`) | Chuyển khoản nội bộ | `TransferService.java` (khoá 2 account theo thứ tự deterministic), `TransferRetryPolicy.java`, `api/TransferController.java` |
| `deposit/` | Mở/tính lãi/đáo hạn tiền gửi | `DepositContractService.java`, `AccrualService.java` (⚠ xem F-03), `DepositApplicationService.java`, `DepositRetryPolicy.java`, `DepositController.java` |
| `lending/` (+`api/`) | Giải ngân/trả nợ/quá hạn/default khoản vay | `LoanContractService.java`, `LoanApplicationService.java` (batch job không qua `moneyCommandTemplate` — xem F-08), `LoanRetryPolicy.java`, `api/LendingController.java` |
| `limits/` | Kiểm tra hạn mức trước khi post | `LimitCheckService.java` |
| `common/` | Hạ tầng dùng chung cho mọi domain tiền | `IdempotentMoneyCommandTemplate.java` (chốt: idempotency + system-mode + retry + transaction boundary cho mọi lệnh tiền), `CoreBankException.java`, `IdempotencyConflictException.java`, `MoneyCommandRetryPolicy.java` |
| `integration/` | Idempotency store, Outbox, Kafka | `IdempotencyService.java`, `OutboxService.java`, `OutboxEventPublisher.java` (⚠ giữ tx mở qua Kafka call — F-09), `KafkaConfig.java` |
| `integration/redis/` | Rate limit tiền + cache | `RedisRateLimitService.java` (fail-open khi Redis down), `MoneyWriteRateLimitInterceptor.java`, `MoneyWriteRateLimitWebConfig.java` (danh sách path bị giới hạn) |
| `integration/saga/` | Saga/read-model state | `SagaStateService.java`, `SagaQueryService.java` |
| `ops/iam/` | Role/permission resolver | `IamAuthorizationService.java` (⚠ KHÔNG có test — F-11) |
| `ops/audit/` | Ghi audit trail (actor/action/before-after/hash-chain) | `AuditService.java` (⚠ KHÔNG có test — F-11) |
| `ops/approval/` | Maker/checker cho thao tác nhạy cảm | `ApprovalService.java`, `OpsApprovalController.java`, `OpsExecutionController.java` |
| `ops/hotaccount/` | Slot balance cho tài khoản hot | `HotAccountOpsService.java` (aggregate qua slot đúng), `OpsHotAccountController.java` |
| `ops/reconciliation/` | Đối soát nội bộ/ngoại bộ | `ReconciliationService.java`, `ExternalReconciliationService.java`, `OpsReconciliationController.java` |
| `ops/security/` | Mã hoá dữ liệu KYC nhạy cảm | `CustomerSecretCryptoService.java` (AES-256/128-GCM thật, fail-closed), `CustomerSecretService.java`, `OpsCustomerSecretController.java` |
| `ops/system/` | Chế độ vận hành (RUNNING/EOD_LOCK/MAINTENANCE) | `SystemModeService.java`, `OpsRuntimeModePolicy.java` (⚠ chỉ enforce qua convention — F-08) |
| `ops/maintenance/`, `ops/batch/`, `ops/exception/` | Job vận hành, dead-letter, queue lỗi | `OpsMaintenanceController.java`, `BatchRunService.java`, `ExceptionQueueService.java` |
| `reporting/` | Read-model, snapshot, outbox ops API | `ReadModelProjector.java`, `ReadModelQueryService.java`, `SnapshotService.java`, `OutboxOpsController.java`, `OutboxReportingService.java` |
| `product/` | Product governance/versioning | `ProductGovernanceService.java`, `ProductGovernanceController.java` |
| `security/` | **Toàn bộ auth của app** | `DemoSecurityConfig.java` (⚠ duy nhất, không `@Profile` — F-04) |
| `demo/` | Scaffolding cho showcase, tách biệt hoàn toàn khỏi logic tiền | `demo/api/*`, `demo/application/DemoSetupService.java` |
| `config/` | Hạ tầng deploy | `RenderDatabaseUrlEnvironmentPostProcessor.java` |

**Schema/migration:** `src/main/resources/db/migration/V1__...sql` → `V26__...sql`
(26 file, V5/V6 là số chưa từng dùng — không phải drift). Trigger chặn
UPDATE/DELETE trên bảng ledger nằm ở `V2__base_schema.sql:274-306`
(`forbid_append_only_mutation()`).

**Test layout:** `src/test/java/com/corebank/corebank_api/<package>/` soi gương
`src/main/java/...`. 57 file test / 100 file main; 49/57 dùng Testcontainers
Postgres thật (`@Import(TestcontainersConfiguration.class)`).

---

## 2. Sổ ghi findings (để kiểm tra chéo)

| ID | Mức độ | Khu vực | File:Line | Mô tả ngắn | Trạng thái |
|---|---|---|---|---|---|
| F-01 | CRITICAL | authz | `payment/api/PaymentController.java` (toàn bộ mapping), `transfer/api/TransferController.java:24-32`, `lending/api/LendingController.java:24-42`; gốc rễ: `security/DemoSecurityConfig.java:38-46` | Chỉ gate theo role (`hasAnyRole`), không có kiểm tra chủ sở hữu account trong service layer (`payment/HoldService.java` chỉ dùng `actor` để log) → user role thấp nhất chuyển được tiền của account bất kỳ | VERIFIED (đọc trực tiếp `PaymentController.java` + `HoldService.java`, xác nhận không có check ownership) |
| F-02 | CRITICAL | audit | `payment/PaymentApplicationService.java:46,101,129,155,200`, `transfer/TransferService.java:59` | `actor` lấy từ request body (client tự khai), không lấy từ `Authentication.getName()` như `ops/security/OpsCustomerSecretController.java:39` và `ops/approval/OpsApprovalController.java:44,73,83` làm đúng | AGENT-REPORTED |
| F-03 | CRITICAL | logic/deposit | `deposit/AccrualService.java:52-131` (đặc biệt dòng 127-130) | `processDailyAccruals` là 1 `@Transactional` bao ngoài; catch-per-item bên trong không cứu được vì `ledgerCommandService.postJournal()` (dòng 39-40, `@Transactional` propagation mặc định REQUIRED) đánh dấu rollback-only khi lỗi → `UnexpectedRollbackException` khi method return, mất TOÀN BỘ batch accrual hôm đó, không chỉ hợp đồng lỗi | VERIFIED (đọc trực tiếp `AccrualService.java` + xác nhận `LedgerCommandService.java:39-40` là `@Transactional` propagation mặc định) |
| F-04 | CRITICAL | auth | `security/DemoSecurityConfig.java` (toàn file, không có `@Profile`) | Cơ chế xác thực DUY NHẤT của app: HTTP Basic + 3 user hardcode, password `{noop}` (plaintext), không BCrypt/JWT nào trong repo | VERIFIED (grep xác nhận đây là `SecurityFilterChain` duy nhất trong repo) |
| F-05 | HIGH | authz | `security/DemoSecurityConfig.java:22-31` | CSRF bị ignore đúng vào endpoint tiền, trong khi không có `SessionCreationPolicy.STATELESS` nào cấu hình → tiền đề "stateless nên tắt CSRF an toàn" không được đảm bảo | AGENT-REPORTED |
| F-06 | HIGH | authz | `payment/api/PaymentController.java:74-102` | IDOR: `getPaymentOrder`/`listPaymentOrders` không kiểm tra chủ sở hữu | AGENT-REPORTED |
| F-07 | MEDIUM | security/config | `application-showcase.yml:22`, `.env.example`, gốc rễ `security/DemoSecurityConfig.java:16,48-55` | `corebank.showcase.token` được khai báo, mô tả là "token mở khoá" nhưng KHÔNG có dòng code nào đọc giá trị này — gate thực chất là `denyAll()` tĩnh theo cờ boolean | VERIFIED (đọc trực tiếp `DemoSecurityConfig.java`, grep toàn repo không tìm thấy nơi đọc giá trị token) |
| F-08 | MEDIUM | reliability | `common/IdempotentMoneyCommandTemplate.java:126` (nơi enforce), `lending/LoanApplicationService.java:162-259` (nơi bypass, tự gọi tay dòng 164,216) | System-mode guard (chặn ghi khi EOD_LOCK/MAINTENANCE) chỉ hoạt động nếu code đi qua `moneyCommandTemplate` — không phải interceptor/AOP toàn cục; batch job lending đã phải tự nhớ gọi tay, chứng minh pattern dễ bị quên ở endpoint mới | AGENT-REPORTED |
| F-09 | MEDIUM | reliability | `integration/OutboxEventPublisher.java` (`processPendingEvents`, khoảng dòng 60-95) | Giữ transaction DB mở trong lúc gọi Kafka đồng bộ/blocking (`future.get()`) — rủi ro latency/giữ lock | AGENT-REPORTED |
| F-10 | LOW | reliability | `lending/LoanApplicationService.java:162-259` | Batch job (`markOverdueInstallments`, `markContractDefaulted`) không dùng `MoneyCommandRetryPolicy` chung — lỗi deadlock/serialization thì fail thẳng, không retry, khác với pattern dùng ở payment/transfer/deposit | AGENT-REPORTED |
| F-11 | LOW | test-coverage | `ops/iam/IamAuthorizationService.java`, `ops/audit/AuditService.java` | Hai class xuyên suốt quan trọng nhất về an toàn — không có test nào (`src/test/.../ops/iam`, `.../ops/audit` không tồn tại) | AGENT-REPORTED |
| F-12 | LOW | test-coverage | `account/*.java`, `customer/*.java` | Không có test riêng (chỉ được exercise gián tiếp như fixture trong test payment/ledger) | AGENT-REPORTED |
| F-13 | LOW | ops | `security/DemoSecurityConfig.java:76-79` | `demo_admin` gộp ADMIN+MAKER+APPROVER trên 1 account → không demo được segregation-of-duties bằng account có sẵn | AGENT-REPORTED |
| F-14 | LOW | docs | `README.md` (mục "Quick Credibility Evidence") → `showcase-output/latest-showcase-report.md` | File này bị `showcase-output/.gitignore` loại bỏ, không tồn tại trong repo → dead link trên GitHub | VERIFIED (đọc trực tiếp `showcase-output/.gitignore` = `*` + `!.gitignore`) |
| F-15 | LOW | ops | `Dockerfile` | Container chạy bằng root, không có `USER` directive | VERIFIED (đọc trực tiếp Dockerfile) |
| F-16 | INFO | design | `ledger/LedgerCommandService.java:147-178` (`validateBalancedJournal`) | Bất biến debit=credit chỉ enforce ở tầng Java, KHÔNG có CHECK/trigger DB (khác với tính bất biến lịch sử — F-immutable, có trigger DB thật ở `V2__base_schema.sql:274-306`) | AGENT-REPORTED |
| F-17 | INFO | test-gap | `deposit/AccrualService.java` liên quan | Không có test integration cho `processDailyAccruals` — nếu có, sẽ bắt được F-03 ngay | VERIFIED (không tìm thấy file test cho `AccrualService` khi liệt kê `src/test/.../deposit/`) |

### Đã kiểm tra và XÁC NHẬN ỔN (không phải finding, liệt kê để khỏi bị review lại)

| Khu vực | File:Line | Vì sao ổn |
|---|---|---|
| Ledger immutable | `V2__base_schema.sql:274-306` | Trigger `forbid_append_only_mutation()` chặn UPDATE/DELETE ở tầng DB trên `ledger_journals`, `ledger_postings`, `hold_events`, `payment_events`, `audit_events` |
| Idempotency | `integration/IdempotencyService.java:159-171`; test `PaymentIdempotencyIntegrationTest.java:136-175` | Cùng key + payload khác nhau bị reject, có test Postgres thật chứng minh, kể cả race concurrent (dòng 215-249) |
| Khoá deterministic | `account/AccountBalanceRepository.java:80-108`, `ledger/LedgerCommandService.java:43-59`, `ledger/HotAccountSlotRuntimeService.java:40-70` | Sort UUID trước khi `FOR UPDATE`; test 24 thread thật chứng minh không deadlock (`TransferServiceIntegrationTest.java:432-499`) |
| Outbox pattern | `common/IdempotentMoneyCommandTemplate.java:114-143`, `integration/OutboxEventPublisher.java:95` | Ghi cùng transaction nghiệp vụ; publish Kafka chỉ sau commit; không có đường publish trực tiếp nào khác trong repo |
| Mã hoá dữ liệu nhạy cảm | `ops/security/CustomerSecretCryptoService.java` | AES/GCM thật (nonce 12 byte, tag 128-bit, AAD), fail-closed 503 khi thiếu master key, DB chỉ lưu ciphertext (`V3__phase1_hardening.sql:180-193`) |
| SQL injection | mọi module có dynamic query đã kiểm | Toàn bộ dùng tham số hoá (`JdbcTemplate` args), không nối chuỗi |
| Rate limit | `integration/redis/RedisRateLimitService.java:27-49` | Fail-open đúng như README claim khi Redis down |
| Flyway vs code | `V24`-`V26` đối chiếu `HoldService.java`, `ExternalReconciliationService.java` | Không có drift |

---

## 3. Gợi ý cho agent tiếp theo

- Muốn sửa nhanh nhất theo mức độ nghiêm trọng: **F-01 → F-02 → F-03 → F-04**.
- Trước khi sửa F-01/F-02, đọc `07-financial-invariants.md` mục "What an agent must never do" — sửa ownership check không được phá vỡ invariant idempotency/ledger đã có.
- Khi cross-check một dòng `AGENT-REPORTED`, đổi thành `VERIFIED` (đúng) hoặc `REFUTED` (sai, kèm lý do) ngay trong bảng này thay vì tạo file mới, để map không bị phân mảnh.
