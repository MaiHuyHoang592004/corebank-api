# CoreBank AI Context Pack (Cline Final)

Bộ tài liệu này được viết để cung cấp **đầy đủ context dự án** cho **Cline**, contributor mới, hoặc chính bạn khi quay lại dự án sau một thời gian.

Mục tiêu của pack này:
- Giúp Cline hiểu đúng **bài toán**, **ranh giới hệ thống**, **domain**, **schema**, **workflow**, và **invariants tài chính**
- Giảm việc agent suy diễn sai khi đọc code hoặc viết feature mới
- Tạo một nguồn sự thật ở mức **context + architecture + domain language + operating rules for Cline**
- Biến docs thành **repo operating system** cho vibe coding có kiểm soát

## Điểm khác biệt của bản Cline Final
Bản này không chỉ mô tả kiến trúc, mà còn thêm:
- **Cline operating model** (Plan/Act/Checkpoint/Auto-Approve)
- **Policy kit** (`.clineignore`, command permissions, hooks, workflows)
- **Prompt/task templates** để Cline code đúng hơn
- **Model strategy** (Codex-first, fallback, local privacy mode)
- **Troubleshooting** cho workflow Cline thực tế

## Cách đọc nhanh cho Cline
1. `AGENTS.md`
2. `01-project-overview.md`
3. `04-system-architecture.md`
4. `05-domain-modules.md`
5. `06-database-context.md`
6. `07-financial-invariants.md`
7. `08-core-workflows.md`
8. `09-application-architecture.md`
9. `14-source-of-truth-map.md`
10. `17-execution-plan.md`
11. `18-testing-strategy.md`
12. `19-runtime-failure-modes.md`
13. `20-acceptance-criteria.md`
14. `21-cline-operating-model.md`
15. `22-cline-policy-kit.md`
16. `23-cline-workflows.md`
17. `24-cline-prompts-and-task-templates.md`
18. `25-cline-model-strategy.md`
19. `26-cline-troubleshooting.md`

## Policy kit included
See `policy-kit/` for ready-to-copy repo assets:
- `.clineignore.example`
- `cline-command-permissions.example.json`
- `hooks/pre-attempt-completion.sh`
- `workflows/feature-delivery.md`
- `workflows/schema-change.md`
- `workflows/bugfix.md`

## Main project goal
Dự án này là một **core banking / fintech backend portfolio project** theo hướng **production-like**:
- **PostgreSQL** làm source of truth tài chính
- **Spring Boot** làm application/business orchestration
- **Kafka** cho integration async và CQRS projector
- **Redis** cho performance/co-ordination ngắn hạn
- Thiết kế theo hướng **modular monolith trước**, có thể tách service sau

## Final note
Nếu dùng đúng bộ này, Cline sẽ không chỉ “code nhanh” mà còn có khả năng:
- giữ được invariants tài chính
- không phá migration strategy
- không bypass outbox/idempotency/audit
- biết khi nào phải Plan trước khi Act
