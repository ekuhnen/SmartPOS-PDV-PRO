# DIRECT-SALE-TICKET-02A — Larger pickup QR

QR_SIZE_BEFORE: 280x280.

QR_SIZE_AFTER: 320x320.

PRINTER_WIDTH_SAFE: YES. The supported bitmap paths pass the bitmap directly: Sunmi `printBitmap`, Dspread `addBitmap`, and Gertec/Kozen bitmap print lines. No implementation imposes a narrower bitmap width or scales the image. 320 px remains below the common 58 mm thermal raster width; clipping/scaling risk in code is none.

QR_CLIPPING_RISK: NONE in the Android print paths audited.

PDV1_PRESERVED: PASS. Quiet zone remains 4, error correction remains M, black on white, centered, with no interpolation.

The approved ticket hierarchy and duplicated transaction/pickup identifiers are unchanged; only the generated QR dimension increases. Money, payment, backend, contract, and pickup-code derivation are unchanged.

Tests: full Android suite 960/960 PASS (480 debug + 480 release).

assembleDebug: PASS.

Commit: pending.

PHYSICAL_VALIDATION: NOT_RUN_BY_CODEX.
