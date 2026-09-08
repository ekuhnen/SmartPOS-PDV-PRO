# DIRECT-PAY-03 — Explicit payment cancellation recovery

ROOT_CAUSE: `PaymentHandlerActivity` durably updated correlated callbacks, including `CANCELLED`/`REJECTED`, and terminalized the prepared outbox operation, but only `APPROVED` was written to `PaymentResultStore` and consumed by `CheckoutActivity`. A cancellation was therefore consumed or returned without clearing the direct checkout's in-memory recovery gate; the next resume restored the stale blocking state.

CANCEL_RESULT_BEFORE: Attempt/outbox were terminalized when correlated, but `PaymentResultStore` was not populated and `CheckoutActivity.checkPendingPaymentResult()` ignored non-APPROVED results.

CANCEL_RESULT_AFTER: Correlated `CANCELLED` and `CANCELED` are persisted as terminal `CANCELLED`, published with `requestId`, consumed explicitly, and release only the retry gate. `UNKNOWN`, missing correlation, malformed callback, and no callback remain reconciliation-blocked.

CANCEL_CALLBACK_RECEIVED: YES.

CANCEL_PAYMENT_ATTEMPT_PERSISTED: YES.

CANCEL_RESULT_DELIVERED_TO_CHECKOUT: YES after the fix.

CANCEL_RESULT_CONSUMED: YES after the fix.

CANCEL_RESULT_HANDLED: YES after the fix.

CANCELLED_OPERATION_STILL_CLASSIFIED_UNRESOLVED: NO; the prepared direct operation is terminalized as non-retriable `FAILED` with `CANCELLED_PAYMENT`, and is excluded from `WAITING_PAYMENT` recovery.

DIRECT_SALE_OPERATION_STATE_BEFORE_CANCEL: `WAITING_PAYMENT`.

DIRECT_SALE_OPERATION_STATE_AFTER_CANCEL: terminal `FAILED`/`CANCELLED_PAYMENT` audit state; it cannot synchronize as a paid sale.

RETRY_CORRELATION: A new checkout attempt creates a new UUID operation/request/idempotency key. The original `PaymentAttempt` row remains `CANCELLED`.

LATE_APPROVED_POLICY: An APPROVED callback for an attempt already `CANCELLED` or `REJECTED` is converted to `UNKNOWN`, preserved in Room, marks durable reconciliation, and is never silently promoted to a paid sale.

TABLE_COMANDA: The shared handler keeps the existing approved and unknown flows; explicit correlated terminal results use the same safe state policy without changing financial authority.

LOCALIZATION: Added retryable cancellation/start-failure messages in pt-BR, es, en, and gn. New localization gaps: 0. Existing audit findings are pre-existing informational debt.

BACKEND MODIFIED: NO.

FINANCIAL AUTHORITY MODIFIED: NO.

Tests: 956/956 PASS (478 debug + 478 release); targeted recovery tests PASS.

assembleDebug: PASS.

Commit: pending.

PHYSICAL_VALIDATION: NOT_RUN_BY_CODEX.
