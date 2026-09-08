# DIRECT-PAY-03A — Stale reconciliation recovery

ROOT_CAUSE: The durable marker in `DirectPaymentReconciliationStore` was global, while `getUnresolvedDirectPaymentState()` only inspected `WAITING_PAYMENT`/`NEEDS_RECONCILIATION` sales and had no repair pass for a correlated `PaymentAttempt=CANCELLED`. Legacy residue therefore kept the checkout blocked after the callback had safely failed.

ACTUAL_BLOCK_SOURCE: `CheckoutActivity.updatePayButtonState()` combines the global reconciliation marker with `DirectCheckoutViewModel.isPaymentBlocked`; the ViewModel populated that state from `SaleOutboxRepository.getUnresolvedDirectPaymentState()`.

DURABLE_MARKER_FOUND_IN_LOGIC: YES.

UNRESOLVED_SALE_FOUND_IN_LOGIC: YES.

PAYMENT_ATTEMPT_CORRELATION_KEY: `PaymentAttemptEntity.reference`.

DIRECT_SALE_OPERATION_KEY: `LocalSaleEntity.localId`.

CORRELATION_IS_EXACT: YES (`reference == localId`).

STALE_CANCELLED_CORRELATION: NOT_SUPPORTED_BEFORE.

LEGACY_CANCELLED_RECOVERY_AFTER: PASS. `restoreDurableRecovery()` now atomically scans legacy waiting/reconciliation sales and terminalizes only correlated `CANCELLED`, `REJECTED`, `DECLINED`, or `FAILED_TO_START` attempts as existing `FAILED_PERMANENT` non-paid operations.

CANCELLED_MARKER_SAFE_CLEAR: PASS when the marker has the same operation ID and a safe terminal reason. Protected `APPROVED_WITHOUT_CORRELATION`, `UNKNOWN`, missing-correlation, and late-approved markers are never cleared.

REJECTED_RECOVERY: PASS.

FAILED_TO_START_RECOVERY: PASS.

UNKNOWN_REMAINS_BLOCKED: PASS.

MISSING_ATTEMPT_REMAINS_BLOCKED: PASS.

APPROVED_REMAINS_PROTECTED: PASS; approved waiting sales continue through the existing approved recovery path.

MULTIPLE_OPERATION_ISOLATION: PASS; each sale is evaluated by its own `localId/reference`, so a safe K1 does not release an unresolved K2.

APP_RESTART_RECOVERY: PASS.

NEW_PAYMENT_AFTER_RECOVERY: PASS.

NEW_REQUEST_ID: PASS; the existing UUID-at-touch flow creates K2 and retains K1 audit history.

DIRECT_PAY_03: PASS.

DIRECT_MONEY_02: PASS.

MESA_COMANDA_REGRESSION: PASS.

BLOCK_REASON_DIAGNOSTICS: ViewModel recovery now exposes `PAYMENT_PENDING`, `PAYMENT_UNKNOWN`, `APPROVED_REQUIRES_RECONCILIATION`, and `MISSING_PAYMENT_ATTEMPT` internally.

BACKEND MODIFIED: NO.

FINANCIAL AUTHORITY MODIFIED: NO.

DATABASE WIPED: NO.

NEW_LOCALIZATION_GAPS: 0.

Tests: 956/956 PASS (478 debug + 478 release); targeted recovery tests PASS.

assembleDebug: PASS.

Commit: pending.

PHYSICAL_VALIDATION: NOT_RUN_BY_CODEX.
