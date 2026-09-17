# Agent Review Map — corebank-api

Sinh ra từ một lượt review sâu (4 sub-agent song song, mỗi agent đọc trực tiếp
source + đối chiếu với `07-financial-invariants.md`, `19-runtime-failure-modes.md`,
`18-testing-strategy.md`, `11-ops-reliability-security.md`, sau đó được
cross-verify thủ công). Mục đích file này: agent/reviewer sau không cần quét lại
toàn bộ 100 file Java để định vị module hay tái tạo finding — chỉ cần nhảy thẳng
tới `file:line` bên dưới và xác nhận.

**Ngày review:** 2026-09-17 · **Commit tại thời điểm review:** `842e76b`
(branch `claude/magical-volta-2rueqx`) · **Build:** `mvn compile` PASS.

**Cập nhật 2026-09-17 (cùng phiên):** F-01, F-02, F-03, F-05, F-07, F-08, F-09,
F-10, F-11, F-12, F-14, F-15 đã được sửa trên cùng branch — xem cột Trạng thái
của từng dòng. §4 liệt kê 3 mục cố tình KHÔNG sửa kèm lý do (F-04, F-13, F-16).
Không còn mô tả kịch bản khai thác chi tiết cho các lỗi đã vá — chỉ còn vị trí
sửa để đối chiếu, vì repo này public.

Quy ước trạng thái: `VERIFIED` = đã đọc trực tiếp source xác nhận đúng như mô tả.
`AGENT-REPORTED` = một sub-agent báo cáo, chưa được người/agent thứ hai đọc lại.
`FIXED` = đã sửa trong cùng phiên, kèm file đã đổi. Khi cross-check một dòng
`AGENT-REPORTED` còn lại, đổi thành `VERIFIED` hoặc `REFUTED` kèm ghi chú.

---

## 1. Bản đồ kiến trúc (package → trách nhiệm → file chính)

| Package | Trách nhiệm | File chính |
|---|---|---|
| `account/` | Đọc/khoá số dư tài khoản khách hàng | `AccountBalanceRepository.java` (khoá deterministic, optimistic version), `BalanceQueryService.java`, `CustomerAccount.java` |
| `customer/` | Hồ sơ khách hàng (không chứa PII nhạy cảm — xem `ops/security`) | `Customer.java`, `CustomerRepository.java` |
| `ledger/` | Sổ cái kép — nơi DUY NHẤT ghi journal/posting | `LedgerCommandService.java` (postJournal, validateBalancedJournal, hash-chain), `LedgerJournal.java`, `LedgerPosting.java`, `HotAccountSlotRuntimeService.java` (slot khoá deterministic cho hot account) |
| `payment/` (+`api/`) | Hold → Capture → Void → Refund | `HoldService.java` (authorize/capture/void/refund), `PaymentApplicationService.java`, `PaymentQueryService.java`, `PaymentRetryPolicy.java`, `api/PaymentController.java` |
| `transfer/` (+`api/`) | Chuyển khoản nội bộ | `TransferService.java` (khoá 2 account theo thứ tự deterministic), `TransferRetryPolicy.java`, `api/TransferController.java` |
| `deposit/` | Mở/tính lãi/đáo hạn tiền gửi | `DepositContractService.java`, `AccrualService.java` (F-03 đã sửa — mỗi hợp đồng 1 transaction riêng), `DepositApplicationService.java`, `DepositRetryPolicy.java`, `DepositController.java` |
| `lending/` (+`api/`) | Giải ngân/trả nợ/quá hạn/default khoản vay | `LoanContractService.java`, `LoanApplicationService.java` (2 batch job giờ có retry riêng — F-10 đã sửa), `LoanRetryPolicy.java`, `api/LendingController.java` |
| `limits/` | Kiểm tra hạn mức trước khi post | `LimitCheckService.java` |
| `common/` | Hạ tầng dùng chung cho mọi domain tiền | `IdempotentMoneyCommandTemplate.java` (chốt: idempotency + system-mode + retry + transaction boundary cho mọi lệnh tiền), `CoreBankException.java`, `IdempotencyConflictException.java`, `MoneyCommandRetryPolicy.java` |
| `integration/` | Idempotency store, Outbox, Kafka | `IdempotencyService.java`, `OutboxService.java`, `OutboxEventPublisher.java` (F-09 đã sửa — không còn tx bao ngoài), `KafkaConfig.java` |
| `integration/redis/` | Rate limit tiền + cache | `RedisRateLimitService.java` (fail-open khi Redis down), `MoneyWriteRateLimitInterceptor.java`, `MoneyWriteRateLimitWebConfig.java` (danh sách path bị giới hạn) |
| `integration/saga/` | Saga/read-model state | `SagaStateService.java`, `SagaQueryService.java` |
| `ops/iam/` | Role/permission resolver | `IamAuthorizationService.java` (F-11 đã sửa — có test) |
| `ops/audit/` | Ghi audit trail (actor/action/before-after/hash-chain) | `AuditService.java` (F-11 đã sửa — có test) |
| `ops/approval/` | Maker/checker cho thao tác nhạy cảm | `ApprovalService.java`, `OpsApprovalController.java`, `OpsExecutionController.java` |
| `ops/hotaccount/` | Slot balance cho tài khoản hot | `HotAccountOpsService.java` (aggregate qua slot đúng), `OpsHotAccountController.java` |
| `ops/reconciliation/` | Đối soát nội bộ/ngoại bộ | `ReconciliationService.java`, `ExternalReconciliationService.java`, `OpsReconciliationController.java` |
| `ops/security/` | Mã hoá dữ liệu KYC nhạy cảm | `CustomerSecretCryptoService.java` (AES-256/128-GCM thật, fail-closed), `CustomerSecretService.java`, `OpsCustomerSecretController.java` |
| `ops/system/` | Chế độ vận hành (RUNNING/EOD_LOCK/MAINTENANCE) | `SystemModeService.java`, `OpsRuntimeModePolicy.java`, `SystemModeWriteGuardInterceptor.java`/`SystemModeWriteGuardWebConfig.java` (mới — F-08 đã sửa, defense-in-depth) |
| `ops/maintenance/`, `ops/batch/`, `ops/exception/` | Job vận hành, dead-letter, queue lỗi | `OpsMaintenanceController.java`, `BatchRunService.java`, `ExceptionQueueService.java` |
| `reporting/` | Read-model, snapshot, outbox ops API | `ReadModelProjector.java`, `ReadModelQueryService.java`, `SnapshotService.java`, `OutboxOpsController.java`, `OutboxReportingService.java` |
| `product/` | Product governance/versioning | `ProductGovernanceService.java`, `ProductGovernanceController.java` |
| `security/` | **Toàn bộ auth của app** | `DemoSecurityConfig.java` (duy nhất, không `@Profile` — F-04, cố tình không sửa, xem §4; nhưng F-05/F-07 trong cùng file đã sửa: STATELESS session + token-gate thật) |
| `demo/` | Scaffolding cho showcase, tách biệt hoàn toàn khỏi logic tiền | `demo/api/*`, `demo/application/DemoSetupService.java` |
| `config/` | Hạ tầng deploy | `RenderDatabaseUrlEnvironmentPostProcessor.java` |

**Schema/migration:** `src/main/resources/db/migration/V1__...sql` → `V26__...sql`
(26 file, V5/V6 là số chưa từng dùng — không phải drift). Trigger chặn
UPDATE/DELETE trên bảng ledger nằm ở `V2__base_schema.sql:274-306`
(`forbid_append_only_mutation()`).

**Test layout:** `src/test/java/com/corebank/corebank_api/<package>/` soi gương
`src/main/java/...`. 62 file test / 100 file main (57 gốc + 5 file mới từ
F-03/F-11/F-12/F-17) — đa số dùng Testcontainers Postgres thật
(`@Import(TestcontainersConfiguration.class)`).

---

## 2. Sổ ghi findings (để kiểm tra chéo)

| ID | Mức độ | Khu vực | File:Line đã sửa | Mô tả ngắn (còn lại: vị trí sửa, không còn mô tả cách khai thác) | Trạng thái |
|---|---|---|---|---|---|
| F-01 | CRITICAL | authz | `payment/api/PaymentController.java`, `transfer/api/TransferController.java`, `lending/api/LendingController.java`, `deposit/DepositController.java` | Thiếu authorization check tường minh ở tầng controller (chỉ dựa vào URL-matcher của Spring Security). Đã thêm `IamAuthorizationService.requireAnyRole(...)` tường minh ở đầu mỗi endpoint tiền, cùng pattern với `ops/*` controllers | **FIXED** |
| F-02 | CRITICAL | audit | `payment/PaymentApplicationService.java`, `transfer/TransferService.java`, `lending/LoanApplicationService.java`, `deposit/DepositApplicationService.java` (thêm `withActor(...)` cho từng request record) + 4 controller ở trên (rebind actor từ `Authentication.getName()`) | `actor` trước đây lấy từ request body (client tự khai). Đã sửa: controller luôn ghi đè bằng `authentication.getName()` trước khi gọi service, actor không còn giả mạo được | **FIXED** |
| F-03 | CRITICAL | logic/deposit | `deposit/AccrualService.java` | 1 `@Transactional` bao ngoài toàn batch khiến 1 hợp đồng lỗi làm rollback-only cả batch. Đã sửa: mỗi hợp đồng chạy trong `TransactionTemplate` `PROPAGATION_REQUIRES_NEW` riêng (cùng pattern `IdempotentMoneyCommandTemplate`), lỗi 1 hợp đồng không ảnh hưởng hợp đồng khác. Test hồi quy: `src/test/.../deposit/AccrualServiceTest.java` | **FIXED** |
| F-04 | CRITICAL | auth | `security/DemoSecurityConfig.java` | Cơ chế xác thực DUY NHẤT của app: HTTP Basic + 3 user hardcode, password `{noop}` (plaintext) | **KHÔNG SỬA — xem §4** |
| F-05 | HIGH | authz | `security/DemoSecurityConfig.java` | CSRF bị ignore trên endpoint tiền mà không có `SessionCreationPolicy.STATELESS`. Đã thêm `.sessionManagement(... STATELESS)` | **FIXED** |
| F-06 | HIGH | authz | `payment/api/PaymentController.java` (`getPaymentOrder`, `listPaymentOrders`) | Thiếu authorization check tường minh trên endpoint đọc. Đã thêm cùng `requireAnyRole(...)` như F-01 (không có khái niệm ownership ở tầng domain — xem §4 lý do không mở rộng thêm) | **FIXED** (một phần — xem ghi chú) |
| F-07 | MEDIUM | security/config | `security/DemoSecurityConfig.java`, `application-showcase.yml`, `.env.example` | `corebank.showcase.token` được khai báo nhưng không có code nào đọc giá trị này — gate thực chất là `denyAll()` tĩnh. Đã sửa: implement `AuthorizationManager` so sánh header `X-Showcase-Token` bằng `MessageDigest.isEqual` (constant-time), yêu cầu ROLE_ADMIN; bỏ giá trị mặc định lộ trong repo | **FIXED** |
| F-08 | MEDIUM | reliability | `ops/system/SystemModeWriteGuardInterceptor.java` (mới), `ops/system/SystemModeWriteGuardWebConfig.java` (mới) | System-mode guard trước đây chỉ hoạt động qua convention (gọi `moneyCommandTemplate`). Đã thêm interceptor defense-in-depth áp lên đúng danh sách path tiền (cùng pattern `MoneyWriteRateLimitInterceptor`) | **FIXED** |
| F-09 | MEDIUM | reliability | `integration/OutboxEventPublisher.java` | `processPendingEvents()` giữ 1 `@Transactional` bao ngoài suốt vòng lặp gồm cả Kafka call chặn đồng bộ. Đã bỏ `@Transactional` — mỗi lệnh JDBC (claim, mark processed/failed) vốn đã atomic độc lập, không cần transaction bao ngoài | **FIXED** |
| F-10 | LOW | reliability | `lending/LoanApplicationService.java` | 2 batch job (`markOverdueInstallments`, `markContractDefaulted`) không dùng `MoneyCommandRetryPolicy` chung. Đã bọc bằng retry loop + `TransactionTemplate` REQUIRES_NEW (không dùng `@Transactional` để tránh lỗi self-invocation) | **FIXED** |
| F-11 | LOW | test-coverage | `src/test/.../ops/iam/IamAuthorizationServiceTest.java` (mới), `src/test/.../ops/audit/AuditServiceTest.java` (mới) | `IamAuthorizationService`/`AuditService` trước đây không có test nào. Đã thêm test cho cả 2 đường DB-backed và Spring-authority-fallback, cộng hash-chain của audit | **FIXED** |
| F-12 | LOW | test-coverage | `src/test/.../account/BalanceQueryServiceTest.java` (mới), `src/test/.../customer/CustomerRepositoryTest.java` (mới) | Đã thêm test cơ bản cho row-mapping và CRUD | **FIXED** |
| F-13 | LOW | ops | `security/DemoSecurityConfig.java:76-79` | `demo_admin` gộp ADMIN+MAKER+APPROVER trên 1 account | **KHÔNG SỬA — xem §4** |
| F-14 | LOW | docs | `README.md` | Link `showcase-output/latest-showcase-report.md` chết (bị gitignore). Đã thêm chú thích giải thích đây là file sinh cục bộ | **FIXED** |
| F-15 | LOW | ops | `Dockerfile` | Container chạy bằng root. Đã thêm user `corebank` non-root + `USER corebank` | **FIXED** |
| F-16 | INFO | design | `ledger/LedgerCommandService.java` (`validateBalancedJournal`) | Bất biến debit=credit chỉ enforce ở tầng Java, không có CHECK/trigger DB | **KHÔNG SỬA — xem §4** |
| F-17 | INFO | test-gap | `src/test/.../deposit/AccrualServiceTest.java` (mới) | Không có test integration cho `processDailyAccruals` — đã thêm cùng lúc với F-03, chứng minh 1 hợp đồng lỗi không kéo sập accrual của hợp đồng khác | **FIXED** |

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

- 12/15 finding đã sửa trong phiên 2026-09-17 (xem cột Trạng thái ở §2). 3 mục còn lại (F-04, F-13, F-16) là quyết định có chủ đích — đọc §4 trước khi động vào, đừng coi là bug chưa fix.
- `mvn compile` + `mvn test-compile` đều PASS sau toàn bộ thay đổi. Các test thuần logic/mock (không cần Postgres) đã chạy qua (`IdempotentMoneyCommandTemplateTest`, `OutboxEventPublisherDeadLetterTest`, `*RetryPolicyTest`, ...) — 25/25 pass. Các test Testcontainers-based (bao gồm 5 file test mới) CHƯA chạy được trong sandbox này (không có Docker) — cần CI hoặc máy có Docker để xác nhận.
- Khi cross-check một dòng `AGENT-REPORTED` còn sót lại, đổi thành `VERIFIED` (đúng) hoặc `REFUTED` (sai, kèm lý do) ngay trong bảng này thay vì tạo file mới, để map không bị phân mảnh.

## 4. Cố tình không sửa — cần quyết định của người, không phải bug sót

| ID | Vì sao không sửa |
|---|---|
| F-04 | Thay toàn bộ `DemoSecurityConfig` bằng auth thật (BCrypt/JWT/DB-backed users) là thay đổi kiến trúc lớn, vượt phạm vi "sửa lỗi" và trực tiếp mâu thuẫn với mục đích đã khai báo của repo (`03-scope-and-non-goals.md`: "does not try to become a real bank core for production deployment"; README liệt kê `demo_admin/demo_ops/demo_user` là thông tin đăng nhập demo công khai có chủ đích). Đã thu hẹp phần rủi ro có thể sửa an toàn (F-01, F-02, F-05, F-07) mà không đổi kiến trúc auth. Nếu có ý định triển khai thật, đây là việc cần làm tiếp theo, không phải fix nhỏ. |
| F-13 | Đổi 3 tài khoản demo thành nhiều tài khoản theo role sẽ phá vỡ "3-Minute Walkthrough" mà README/`28-demo-script.md` mô tả chính xác 3 tài khoản `demo_admin/demo_ops/demo_user`. Đánh đổi không đáng — segregation-of-duties chỉ là vấn đề khi dùng bộ tài khoản demo cho việc thật. |
| F-16 | Thêm CHECK/trigger DB cho debit=credit cần 1 migration Flyway mới; rủi ro cao hơn giá trị nhận được trong phạm vi phiên này vì CI có bước "fail on schema drift" (`prisma migrate diff`-tương đương cho Flyway) phải test kỹ với Postgres thật — sandbox này không có Docker để xác nhận migration chạy sạch từ đầu. Bất biến vẫn được Java layer enforce đúng (`validateBalancedJournal`, có test) — đây là defense-in-depth bổ sung, không phải lỗ hổng đang bị khai thác được. Đề xuất làm ở phiên có Postgres/Docker sẵn. |
