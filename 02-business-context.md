# 02. Business Context

## Bài toán mà hệ thống giải quyết
Hệ thống đóng vai trò là **core processing layer** cho một tổ chức tài chính giả định.

Nó phải trả lời được các câu hỏi:
- Khách hàng là ai?
- Họ có những tài khoản nào?
- Số dư hiện tại là bao nhiêu?
- Tiền đã bị giữ (hold) hay đã thật sự hạch toán?
- Khoản tiền này đi từ đâu đến đâu?
- Nếu xảy ra lỗi thì hoàn tác bằng cách nào?
- Hợp đồng tiền gửi/khoản vay đang ở trạng thái gì?
- Giao dịch này có cần phê duyệt không?
- Có vượt hạn mức không?
- Sự kiện nào cần publish ra ngoài?

## Vai trò của hệ thống
Hệ thống này là **backend lõi**, không phải app internet banking/mobile banking hoàn chỉnh.

Nó phục vụ cho:
- teller/admin dashboard
- digital banking channel
- payment gateway/merchant flows
- deposit/loan management
- internal ops/reconciliation
- event-driven downstream systems

## Persona chính
### 1. Customer
- mở tài khoản
- nạp/rút/chuyển tiền
- gửi tiết kiệm
- vay tiền
- trả nợ

### 2. Merchant
- tạo payment order
- authorize/capture/void/refund payment

### 3. Operations staff
- duyệt nghiệp vụ
- xử lý exception
- chạy EOD/BOD
- đối soát

### 4. AI/engineering agent
- đọc context
- thêm feature
- sửa bug
- giữ đúng invariant tài chính

## Business capability cốt lõi
- customer onboarding
- account lifecycle
- ledger posting
- payment processing
- fund reservation
- deposit management
- lending management
- approval workflow
- limit control
- outbox/event integration
- reconciliation and auditability
