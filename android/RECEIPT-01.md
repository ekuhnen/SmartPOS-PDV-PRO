# RECEIPT-01 — Automatic final table receipt

## Audit

- AUTO_TRANSACTION_RECEIPT_BEFORE: YES.
- AUTO_FINAL_RECEIPT_BEFORE: YES in source, but vulnerable to cancellation.
- MANUAL_FINAL_RECEIPT_AVAILABLE: YES.
- FINAL_RECEIPT_IMPLEMENTATION: `ComandaClosingReceiptRenderer.render`, called
  by `TableCheckoutBottomSheet.printClosingReceipt`.

`CheckoutViewModel` handles matching `checkoutResultEvents` after settlement,
refreshes canonical detail, then sets `paymentSuccess`. Closed events also set
`isComandaClosed` and zero balance. Cash and approved provider payments converge
on this path. The sheet printed a transaction receipt, checked full payment,
launched the closing receipt, acknowledged success and immediately opened the
fiscal dialog, whose callback dismisses the sheet and finishes the activity.

History: `18d22a1` introduced synchronous automatic closing receipt printing.
`f55e21d` added canonical issuer fetching using `lifecycleScope.launch`, leaving
the caller's immediate fiscal dialog unchanged. This introduced a cancellation
race: dismissal/finish can destroy the fragment before receipt fetch and print
complete. The automatic invocation was never removed. This is a code/history
finding, not a physical reproduction of the reported P8 session.

An additional issue existed from `18d22a1`: `PrinterHelper.printReceipt` catches
printer exceptions and returns Unit, so the caller's `runCatching.onSuccess`
could persist PRINTED even when dispatch failed or no printer was detected.

## Restored sequence

The sheet captures receipt state/items/allocations and acknowledges success
before any refresh or printing. The existing acknowledged `paymentSuccess`
prevents replay on ordinary StateFlow updates and lifecycle resume. It then:

1. Prints the payment receipt.
2. If canonical `balanceBaseMinor == 0L`, awaits the complete closing receipt.
3. Continues the existing fiscal/close flow, even on non-financial print failure.

Dismissal is disabled during this sequence. The fiscal dialog cannot destroy
the fragment while its own closing receipt is still being fetched.

Before automatic receipt work, a synchronous preference claim is stored using
the canonical comanda ID. Existing PRINTED markers are respected. The new
ATTEMPTED marker prevents repeated automatic dispatch after failed/unknown
printing; manual reprint bypasses this claim. PRINTED is set only when the
existing printer interface reports successful synchronous dispatch. This is
once-per-comanda automatic dispatch, not proof of exactly-once physical paper
delivery: the existing HAL has no durable paper-completion acknowledgement.
No new Room schema or payment persistence architecture was introduced.

## Canonical sources and isolation

Issuer/customer: existing `GET /api-comandas?recibo=<canonicalComandaId>` through
`CheckoutViewModel.fetchClosingReceipt`. Local issuer preferences are not used.
Issuer legal/trade name, document, address, phone and email are retained; supplied
customer name/document/email are included. Items come from the existing canonical
comanda snapshot hydrator; totals and payment history come from the existing
post-settlement read state. No totals are newly calculated.

The closing renderer now requires explicit base currency and frozen digits;
it never guesses these from selectedCurrency. Its existing explicit formatter
preserves 43,384 PYG even with a BRL-relative PYG rate of 1160. Symbols and number
separators follow existing rules (default PYG output: `Gs. 43.384`).

Receipt and preference identities use comanda ID, never the physical table ID.
Evandro and BK on Mesa 20 remain independent; a standalone comanda needs no
physical table ID. The manual printer listener is unchanged: open comanda uses
`printTableReceipt`, closed comanda uses `printClosingReceipt(reprint = true)`.

## Validation scope

New tests exercise partial/final cash, approved provider settlement, strict zero
balance, delayed receipt/fiscal ordering, repeated claims and reopening preferences,
acknowledged state, printer failure/manual reprint, issuer/customer/items/payments,
explicit PYG, multi-comanda and standalone identity. Lifecycle replay is tested
through the consumed-state and persistent-claim boundaries; no physical fragment
or printer lifecycle test was run.

NEW_LOCALIZATION_GAPS: 0. No translated resources were introduced or changed.
GLOBAL_PREEXISTING_LOCALIZATION_DEBT: en 220 missing keys, gn 298 missing keys;
informational only. Audit consistency passes; currency violations: 0.

PAYMENT_AUTHORITY_MODIFIED: NO. MONEY_CALCULATIONS_MODIFIED: NO.
BACKEND_MODIFIED: NO. CONTRACT_MODIFIED: NO. REALTIME_MODIFIED: NO.
ROOM_SCHEMA_MODIFIED: NO. RC_GENERATED: NO.
PHYSICAL_VALIDATION: NOT_RUN_BY_CODEX.

`gradlew.bat test assembleDebug --continue --console=plain`: BUILD SUCCESSFUL.
Debug: 450/450 PASS. Release: 450/450 PASS. Total: 900 executions with zero
failures, errors or skips. The 13 new receipt tests pass in both variants.
OFFLINE_READ_15: PASS. MONEY-UI-01: 7/7 PASS in each variant.
assembleDebug: PASS. Changed-file whitespace check: PASS.

RECEIPT-01-AUTOMATIC-FINAL-TABLE-RECEIPT: PASS (automated validation).
