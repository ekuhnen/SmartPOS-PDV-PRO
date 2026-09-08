# DIRECT-QR-01 — Versioned product ticket QR

## Audit

HEAD before this change: `dfb8e76`.

The direct-sale ticket was generated in `PrinterHelper.printDirectSaleReceipt` as
an unencrypted plaintext hyphen-delimited string:

`saleId-dateStr-safeOpName-productId-safeProdName-ticketQty-unitFormatted-subtotalFormatted-viaN`

`CURRENT_QR_ENCRYPTED: NO`.
The exact QR bitmap was 160×160 with ZXing margin 1 and no explicit error-correction
hint. If bitmap generation failed, the code called `printer.printQRCode(qrData, 5)`.
Dspread treats that argument as the final QR dimension, so the fallback could be a
5-pixel unusable code. Sunmi forwards it to its service; other HAL implementations
may only log it. There is no shared size unit.

Sale and product IDs are opaque strings. Current local IDs include UUIDs and
`LOCAL-...` identifiers; UUIDs necessarily contain hyphens. Product and operator
names can contain hyphens, spaces and Unicode. The old format replaces hyphens in
names but does not escape IDs, so deterministic legacy parsing cannot be proven.
`LEGACY_V0_DETERMINISTIC_DECODING: NO`; no guessing parser was added.

## Implementation

`DirectSaleQrPayloadCodec` defines deterministic `PDV1:<base64url-json>` encoding.
It writes compact UTF-8 JSON, RFC 4648 URL-safe Base64 with no wrapping or padding,
and the exact fields documented in [DIRECT-SALE-QR-CONTRACT.md](DIRECT-SALE-QR-CONTRACT.md).
The decoder strictly validates prefix, canonical Base64url, UTF-8, one JSON object,
duplicate fields, required types/ranges, timestamp, currency and version 1. It does
not decrypt, infer BRL, parse localized money or split hyphens. `unit_price` is a
JSON decimal number accompanied by `currency`; formatted strings never enter the
wire payload.

When the product carries `price_currency`, the QR copies its numeric selling price
with that currency. For legacy products without the field, it copies the numeric
unit price already prepared by the existing ticket flow and labels it with the
ticket currency. The codec performs no financial recalculation or FX conversion.

`DirectSaleTicketQr` generates a direct 280×280 RGB_565 black/white bitmap with
quiet-zone hint 4 and error correction M. QR generation is primary and no scaling or
interpolation follows it. The ambiguous native `printQRCode(..., 5)` fallback was
removed; generation/print exceptions use the existing visible printer error path.
The existing human-readable product, quantity/copy, unit price, subtotal, purchase
total, payment method, operator/date and pickup instruction lines remain in place.

## Safety and regressions

MONEY-UI-01 remains untouched. Existing financial ticket rendering remains untouched;
only the QR wire payload and bitmap generation changed. No backend, contract,
payment/sale/stock authority, Room schema or printer HAL redesign changed.

## Validation

The 11 DIRECT-QR tests pass in each build variant. They cover round-trip, deterministic
UUID/hyphen handling, product/operator UTF-8, quantity, decimal amount, PYG, prefix,
malformed Base64/JSON/UTF-8, unsupported versions, strict types/duplicates, published
scanner example, 280×280/M/quiet-zone generation and ZXing readability, plus the real
direct-sale printer flow (one bitmap per unit, no native tiny fallback and preserved
human-readable fields).

Full Android suite: debug 461/461 PASS; release 461/461 PASS; 922 executions with
zero failures, errors or skips. `assembleDebug`: PASS. Localization audit reports
zero currency violations and no new resource gaps. Existing global debt remains
informational: en 220 missing keys and gn 298 missing keys.

PHYSICAL_VALIDATION: NOT_RUN_BY_CODEX. A device/scanner and paper-width check remain
necessary for final physical readability confirmation.
