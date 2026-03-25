# 01. Project Overview

## Tên dự án
**CoreBank** — một core banking / fintech backend portfolio project theo hướng production-like.

## Tư tưởng cốt lõi
Dự án không cố clone toàn bộ ngân hàng số, mà tập trung xây phần **backend lõi** để chứng minh:
- tư duy kế toán kép (double-entry)
- balance engine đúng bản chất
- payment authorization / capture / void
- deposit / lending foundation
- approvals / maker-checker
- idempotency, outbox, audit, reconciliation
- readiness cho CQRS, saga, partitioning

## Mục tiêu của dự án
1. Làm một project portfolio mạnh cho vị trí backend/fintech
2. Có kiến trúc đủ sạch để agent AI hỗ trợ phát triển tiếp
3. Có schema đủ rộng để mở rộng thêm feature sau này
4. Bám sát logic “hệ thống vận hành đúng chất ngân hàng”

## Elevator pitch
Đây là một hệ thống backend mô phỏng một lõi ngân hàng hiện đại:
- quản lý khách hàng
- quản lý tài khoản
- ghi sổ kế toán kép
- xử lý thanh toán và giữ tiền
- quản lý tiền gửi và khoản vay
- kiểm soát hạn mức
- hỗ trợ phê duyệt nghiệp vụ
- hỗ trợ integration async
- hỗ trợ read model và hardening production

## Kiểu hệ thống
- **V1:** modular monolith
- **V2:** production-style async integration
- **V3:** selective service extraction nếu cần

## Triết lý phát triển
- Build đúng bản chất trước
- Optimize sau
- Không tách microservice quá sớm
- Giữ PostgreSQL là financial truth
- Tách query model khỏi write model khi cần
