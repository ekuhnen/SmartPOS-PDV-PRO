# DIRECT-SALE-TICKET-02 — Production pickup ticket layout

MERCHANT_NAME_SOURCE: `POS_PREFS` keys `MERCHANT_NAME`, `STORE_NAME`, or `TRADE_NAME` when configured; otherwise the existing application label is used as a safe local fallback. No business identity is invented.

TERMINAL_NAME_SOURCE: `POS_PREFS` keys `TERMINAL_LABEL`, `TERMINAL_NAME`, or `DEVICE_NAME`; otherwise `Build.MODEL` is used as `DEVICE_LABEL_FALLBACK`.

OPERATOR_NAME_SOURCE: authenticated operator name already passed from `CheckoutActivity`/`ReceiptMoneySnapshot`.

TRANSACTION_ID_SOURCE: canonical `saleId` returned by the existing direct-sale result path; printed in full above and below the QR.

TICKET_LAYOUT_UPDATED: YES.

The ticket now prints merchant, `CUPOM DE RETIRADA`, counter instruction, timestamp, PDV, optional operator, full transaction ID, deterministic `XXXX-XXXX` pickup code, emphasized wrapped product name, QTD 1, explicit unit/subtotal, total purchase, payment method, one QR per unit, repeated pickup code and transaction ID, and a final thank-you line. No linear barcode or QR-damage warning is printed.

Each quantity unit remains independent (`Via 1/3`, `Via 2/3`, `Via 3/3`) and receives a distinct code derived from SHA-256 of `PDV1|sale_id|product_id|copy`. QR remains PDV1, 280×280, margin 4, EC M.

Money continues to use the frozen transaction amount/currency and explicit formatting; PrinterHelper does not calculate sale totals. PYG, BRL, and USD semantics are preserved.

BACKEND MODIFIED: NO.

PAYMENT AUTHORITY MODIFIED: NO.

NEW_LOCALIZATION_GAPS: 0.

Tests: 960/960 PASS (480 debug + 480 release).

assembleDebug: PASS.

Commit: pending.

PHYSICAL_VALIDATION: NOT_RUN_BY_CODEX.
