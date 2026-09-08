# Direct-sale pickup ticket QR - PDV1

This document is the scanner interoperability specification. PDV1 is NOT encrypted,
NOT signed and is not proof of payment or stock authority. There is no key/secret.

## Wire format and encoding

`PDV1:<base64url-json>` (case-sensitive ASCII prefix including the colon).
The suffix is UTF-8 JSON encoded with RFC 4648 URL-safe Base64 (`-` and `_`,
not `+` and `/`), without padding, whitespace or line breaks. The Android decoder
requires this canonical unpadded form. Human-readable prices must not be parsed.

## JSON schema

Top level: one JSON object. Required fields:

| Field | JSON type | Meaning and validation |
| --- | --- | --- |
| v | integer | Exactly 1; unsupported versions must be rejected. |
| sale_id | string | Nonblank opaque sale identity. Preserve all characters including hyphens; UUID and LOCAL-prefixed identities are possible. |
| product_id | string | Nonblank opaque product identity; UUIDs/hyphens are valid. |
| product_name | string | Nonblank Unicode product name; spaces, accents and hyphens are preserved. |
| quantity | integer | Positive, at most 2147483647. Units on this ticket, currently 1. |
| unit_price | number | Nonnegative decimal in major units of currency. Read with an exact decimal parser, not locale parsing or binary floating-point if exactness is needed. Not minor units, not a formatted string. |
| currency | string | Three uppercase ASCII letters; the denomination of unit_price. No inferred BRL and no FX conversion during decoding. |
| issued_at | string | Valid UTC print timestamp, exactly YYYY-MM-DDTHH:mm:ssZ. Diagnostic ticket issue time, not financial ordering or payment authority. |
| copy | integer | Positive, at most 2147483647. One-based unit ticket index for this cart line, reset for each product line. Not a reprint count or globally unique redemption key. |

Optional: `operator_name` is a Unicode string, omitted if unavailable (no JSON null).
Duplicate field names and wrong field types are rejected. Unknown additional fields
may be ignored. Field order does not matter to decoders. The deterministic Android
encoder writes the order above, then operator_name, with compact JSON and decimal
numbers without exponent notation. It preserves the supplied decimal scale.

Money provenance: when the cart product supplies price_currency, the QR copies its
numeric selling_price with that explicit currency. For legacy products without that
field, it copies the numeric unit price already prepared by the existing receipt
flow with the ticket currency. It does not recalculate, total, convert or infer money
inside the codec. The QR unit price currency can differ from the purchase/payment
currency. Purchase total and subtotal are not PDV1 fields. No scanner should use the
payload to authorize a payment or reconstruct an authoritative sale total.

## Complete example

Decoded UTF-8 JSON (the exact compact bytes used by the following encoded example):

```json
{"v":1,"sale_id":"550e8400-e29b-41d4-a716-446655440000","product_id":"123e4567-e89b-12d3-a456-426614174000","product_name":"Café - Pão de queijo","quantity":1,"unit_price":11600,"currency":"PYG","issued_at":"2026-09-08T12:00:00Z","copy":2,"operator_name":"João - Núñez"}
```

Encoded QR text (one line):

```text
PDV1:eyJ2IjoxLCJzYWxlX2lkIjoiNTUwZTg0MDAtZTI5Yi00MWQ0LWE3MTYtNDQ2NjU1NDQwMDAwIiwicHJvZHVjdF9pZCI6IjEyM2U0NTY3LWU4OWItMTJkMy1hNDU2LTQyNjYxNDE3NDAwMCIsInByb2R1Y3RfbmFtZSI6IkNhZsOpIC0gUMOjbyBkZSBxdWVpam8iLCJxdWFudGl0eSI6MSwidW5pdF9wcmljZSI6MTE2MDAsImN1cnJlbmN5IjoiUFlHIiwiaXNzdWVkX2F0IjoiMjAyNi0wOS0wOFQxMjowMDowMFoiLCJjb3B5IjoyLCJvcGVyYXRvcl9uYW1lIjoiSm_Do28gLSBOw7rDsWV6In0
```

## Decode algorithm

1. Require the exact `PDV1:` prefix; route unsupported prefixes/versions to an
   unsupported-format error. Do not attempt hyphen splitting.
2. Take the suffix. Require a nonempty string containing only `[A-Za-z0-9_-]`.
3. Base64url-decode. For APIs requiring padding, add `=` until length is a multiple
   of four; a length remainder of one is invalid. Reject invalid trailing bits by
   re-encoding to unpadded Base64url and requiring equality with the input suffix.
4. Decode bytes as UTF-8 strictly; reject invalid byte sequences, do not replace them.
5. Parse exactly one strict JSON object, with no trailing JSON/text or duplicate keys.
6. Validate required fields, types, ranges and `v == 1` as specified above. Integers
   use integer tokens (no fractional/exponent notation). Preserve unit_price as an
   exact decimal with its currency. Ignore unknown additive metadata.
7. Return structured fields. Never split identifiers/names, strip accents, interpret
   a price through the device locale, or automatically convert currencies.

The Android `decode(rawQr)` rejects invalid input with IllegalArgumentException.
Unsupported versions require an explicit decoder update. A PDV1 envelope carrying
`v:2` must not be interpreted as version 1.

## Legacy audit (HEAD dfb8e76)

LEGACY_V0 was plaintext, not encrypted:
`saleId-dateStr-safeOpName-productId-safeProdName-ticketQty-unitFormatted-subtotalFormatted-viaN`.
Date format: `dd/MM/yyyy  HH:mm`; names replaced hyphens with spaces. IDs were not
escaped. IDs can contain hyphens, and legacy money contains locale-specific symbols
and punctuation. The supported ID domain also includes LOCAL-prefixed identities.
There is no framing/escaping contract establishing safe field boundaries.
LEGACY_V0_DETERMINISTIC_DECODING: NO. No guessing legacy parser is provided.
All newly generated pickup tickets use PDV1.

## Bitmap and printing

Before: 160x160, quiet zone 1, no explicit error correction hint (ZXing default).
After: 280x280 pixels, quiet zone minimum 4 modules, error correction M, opaque black
on white, centered using existing printer alignment. QR modules are generated at
integer scale directly by ZXing; no bitmap resizing/interpolation follows generation.

The old native fallback was printQRCode(data, 5). Dspread uses that argument as final
size; Sunmi forwards it to its native service, while some other HAL implementations
only log the call. There is no consistent cross-printer size unit. The fallback is
removed: bitmap generation failure uses the existing visible print-error path.
No 5px native QR is sent and no HAL redesign was performed. Physical readability
still requires terminal/scanner validation; unusually long content increases density.

Existing human-readable ticket lines and their financial values remain unchanged:
product, quantity/copy, unit price, subtotal, purchase total, payment method,
operator/date and pickup instructions. Existing legacy human-price conversions are
outside this QR-only patch. PDV1 does not add any implicit conversion.

PHYSICAL_VALIDATION: NOT_RUN_BY_CODEX.

## Human-readable pickup reference

Each redeemable unit also prints an informational manual pickup reference
above and below the QR in the form `XXXX-XXXX`. It is derived from the first
8 uppercase hexadecimal characters of SHA-256 over the UTF-8 string
`PDV1|<sale_id>|<product_id>|<copy>`, formatted as `XXXX-XXXX`.
The copy number is 1-based and makes each unit distinct. This reference is
for visual support when scanning is unavailable; it is not authentication,
payment authority, or a replacement for the canonical `sale_id`.
