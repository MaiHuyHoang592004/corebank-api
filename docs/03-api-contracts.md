# API Contracts

These are frontend-facing view models for the demo portal.
They are not required to match raw internal CoreBank DTOs exactly.

## DashboardVm
- `totalAvailableBalanceMinor`
- `totalPostedBalanceMinor`
- `currency`
- `accountCount`
- `recentActivityCount`

Current implementation note:
- Dashboard data is currently composed in frontend from `GET /api/demo/accounts` and `GET /api/demo/accounts/{id}/activity`.
- A dedicated `GET /api/demo/dashboard` facade can remain optional for later simplification.

## DashboardActivityItemVm (frontend internal)
- `eventId`
- `eventType`
- `occurredAt`
- `actor`
- `payloadJson`
- `accountId`

Current implementation note:
- Built in frontend from `ActivityRowVm` + source account context.
- Used by dashboard aggregated recent feed and filter tabs.

## DashboardOutcomeHighlightsVm (frontend internal)
- `authorizedCount`
- `capturedCount`
- `voidedCount`
- `transferCount`

Current implementation note:
- Computed from dashboard aggregated top-10 feed.
- No backend API changes required.

## AccountVm
- `accountId`
- `customerId`
- `customerName`
- `productId`
- `productCode`
- `productName`
- `availableBalanceMinor`
- `postedBalanceMinor`
- `currency`
- `status`
- `productType`
- `accountNumber`

## ActivityRowVm
- `eventId`
- `eventType`
- `occurredAt`
- `actor`
- `payloadJson`

## AccountActivityPageIndexVm (frontend internal)
- `uiPage` (1-based)
- `apiPage` (0-based)

Current implementation note:
- Frontend normalizes `searchParams.page` to `uiPage >= 1` and maps to `apiPage = uiPage - 1` before calling `GET /api/demo/accounts/{id}/activity`.

## ActivityPresentationVm (frontend internal)
- `label`
- `amountDirection` (`debit | credit | neutral`)
- `impactHint`

Current implementation note:
- Composed in frontend from `ActivityRowVm` payload + account context.
- Scope-hardening in current slice applies to Payments/Transfers:
  - transfer in/out direction by source/destination account
  - hold authorized/captured/voided semantics
- Event types outside this slice keep neutral fallback presentation.

## AccountLookupVm
- `accountId`
- `accountNumber`
- `customerName`
- `productName`
- `productType`

## InternalTransferRequestVm
- `sourceAccountId`
- `destinationAccountId`
- `amountMajor`
- `description`

## InternalTransferResultVm
- `journalId`
- `status`
- `sourceAccountId`
- `destinationAccountId`
- `amountMinor`
- `currency`
- `sourcePostedBalanceMinor`
- `sourceAvailableBalanceAfterMinor`
- `destinationPostedBalanceMinor`
- `destinationAvailableBalanceAfterMinor`
- `message`

## TransferActionImpactVm (frontend-composed view model)
- `journalId`
- `status`
- `sourceAccountId`
- `destinationAccountId`
- `amountMinor`
- `currency`
- `sourcePostedBeforeMinor`
- `sourcePostedAfterMinor`
- `sourceAvailableBeforeMinor`
- `sourceAvailableAfterMinor`
- `destinationPostedBeforeMinor`
- `destinationPostedAfterMinor`
- `destinationAvailableBeforeMinor`
- `destinationAvailableAfterMinor`

Current implementation note:
- This VM is composed in frontend from:
  - transfer action response (`POST /api/demo/transfers/internal`)
  - account snapshots before action + post-action refresh (`GET /api/demo/accounts/{id}` for source and destination)
- No backend contract changes are required.

## PaymentHoldRequestVm
- `payerAccountId`
- `payeeAccountId`
- `amountMajor`
- `paymentType`
- `description`

## PaymentHoldResultVm
- `paymentOrderId`
- `holdId`
- `payerAccountId`
- `postedBalanceMinor`
- `availableBalanceBeforeMinor`
- `availableBalanceAfterMinor`
- `holdAmountMinor`
- `currency`
- `status`

## PaymentCaptureRequestVm
- `holdId`
- `amountMajor`
- `description`

## PaymentCaptureResultVm
- `paymentOrderId`
- `holdId`
- `journalId`
- `capturedAmountMinor`
- `remainingMinor`
- `holdStatus`
- `paymentStatus`
- `currency`

## PaymentVoidRequestVm
- `holdId`
- `description`

## PaymentVoidResultVm
- `paymentOrderId`
- `holdId`
- `restoredAmountMinor`
- `availableBalanceBeforeMinor`
- `availableBalanceAfterMinor`
- `currency`
- `status`

## PaymentActionImpactVm (frontend-composed view model)
- `action` (`AUTHORIZE | CAPTURE | VOID`)
- `payerAccountId`
- `holdId`
- `paymentOrderId`
- `holdStatus`
- `paymentStatus`
- `amountMinor`
- `currency`
- `availableBeforeMinor`
- `availableAfterMinor`
- `postedBeforeMinor`
- `postedAfterMinor`

Current implementation note:
- This VM is composed in frontend from existing endpoints:
  - action response (`/authorize`, `/capture`, `/void`)
  - payer account refresh (`GET /api/demo/accounts/{id}`)
- No backend contract changes are required for this slice.

## Demo setup endpoint contract
- `POST /api/demo/setup` is a privileged demo initializer/re-seed endpoint.
- Frontend proxy route: `frontend/app/api/demo/setup/route.ts`.
- Requires separate setup credentials (`CORE_BANK_SETUP_USER`, `CORE_BANK_SETUP_PASS`).

## DemoSetupStateVm (frontend internal)
- `backend_unreachable`
- `setup_creds_missing`
- `seed_required`
- `ready`

Current implementation note:
- This is a frontend-only readiness model shared by Dashboard, Payments, Accounts, and Transfers pages.
- It is derived from:
  - backend reachability (`GET /api/demo/accounts` success/failure),
  - setup credential presence in frontend env,
  - seeded demo account availability.

## SetupResultSummaryVm (frontend internal)
- `initializedAt`
- `sourceAccountId`
- `destinationAccountId`
- `paymentAmountMinor`

Current implementation note:
- This summary is parsed from `POST /api/demo/setup` success payload and rendered in setup-status card for deterministic demo feedback.
