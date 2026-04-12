# 31. Sliced Improvement Roadmap (v2)

Mục tiêu: triển khai cải thiện theo thứ tự ưu tiên giảm rủi ro cho dòng tiền, giữ tính đúng đắn ledger và có tiêu chí nghiệm thu rõ ràng.

---

## Slice 1 — Refactor orchestration chung

### Scope
- Chuẩn hóa orchestration vào `MoneyCommandTemplate`
- Dùng shared `ObjectMapper` (inject bean) thay cho khởi tạo cục bộ
- Cleanup drift/comment nhỏ để tránh lệch hành vi

### Deliverables
- `MoneyCommandTemplate` dùng chung cho money-entry command path
- `IdempotentMoneyCommandTemplate` giữ vai trò backward-compatible wrapper
- Các service money path dùng template chung (payment/transfer và các điểm liên quan)
- Guard test để chặn `new ObjectMapper()` drift quay lại ở orchestration scope

### Acceptance
- Build xanh (`mvn -q -DskipTests compile`)
- Test orchestration/idempotency pass
- Không thay đổi semantics posted/available và lock ordering

---

## Slice 2 — Strict product version rollout

### Scope
- **Backfill** dữ liệu hợp đồng cũ còn thiếu `product_version_id`
- **Reporting** cho contract chưa bind version, version drift, và adoption coverage
- **Hard enforcement**: chặn path tạo mới nếu không bind product-version hợp lệ

### Deliverables
- Migration/backfill script idempotent + rollback note
- API/report ops theo dõi coverage và vi phạm
- Enforcement tại command path deposit/lending (và domain có binding)

### Acceptance
- 100% contract trong phạm vi yêu cầu có product-version binding hợp lệ
- Endpoint/report thể hiện được residual risk = 0 cho vùng đã rollout
- Integration tests cho legacy/backfill/enforcement pass

---

## Slice 3 — External reconciliation slice

### Scope
- **Statement import** (ingest ngoài hệ thống)
- **Matching engine** (exact/rule-based + deterministic tie-break)
- **Richer break taxonomy** (phân loại break chi tiết, actionability cao)

### Deliverables
- Pipeline import có checksum/idempotency và tracking batch
- Engine matching với trạng thái: matched/partial/unmatched/duplicate/suspected-error
- Break taxonomy mở rộng + reason codes phục vụ vận hành

### Acceptance
- Re-run cùng statement không tạo duplicate effect
- Match rate và unresolved break count được đo/quan sát được
- Ops có thể truy vết statement line → internal event/journal rõ ràng

---

## Slice 4 — Hot-account / partition actualization

### Scope
- Đưa abstraction hot-account/partition vào **write-path** (không chỉ ops tools)
- Giảm "ops-only" gap giữa vận hành và runtime logic thực tế

### Deliverables
- Write path sử dụng abstraction chung cho account nóng + partition target
- Runtime policy nhất quán với ops policy (không bypass)
- Cơ chế fallback an toàn khi metadata partition/hot-slot stale

### Acceptance
- Concurrency test cho hot-account giữ invariant posted/available
- Partition rollover không làm fail insert/lookup của write-path
- Không còn bước vận hành thủ công bắt buộc cho path thường xuyên

---

## Slice 5 — Security + observability hardening

### Scope
- **Key rotation** cho dữ liệu nhạy cảm
- **Metrics** cho critical flows và lag/retry/failure
- **Operator audit enrichment** (ngữ cảnh actor/session/request/correlation)

### Deliverables
- Quy trình rotate key có kiểm chứng decrypt backward-compatible
- Bộ metric cho money commands, outbox/projector, reconciliation, ops actions
- Audit payload giàu ngữ cảnh, dễ tra cứu forensic

### Acceptance
- Rotation rehearsal thành công (không mất khả năng đọc dữ liệu cũ)
- Dashboard/alert có thể phát hiện sớm lỗi thực thi trọng yếu
- Audit truy vết end-to-end đầy đủ cho sự kiện vận hành nhạy cảm

---

## Dependency & rollout rule

1. Slice 1 trước để chuẩn hóa execution model (giảm drift)
2. Slice 2 kế tiếp để khóa chặt product-governance correctness
3. Slice 3 khi governance đã ổn định để reconciliation không bị nhiễu
4. Slice 4 để đưa tối ưu runtime vào đường ghi thực tế
5. Slice 5 hoàn thiện bảo mật + quan sát + forensic readiness

**Gating:** không mở slice kế tiếp nếu acceptance của slice hiện tại chưa đạt.