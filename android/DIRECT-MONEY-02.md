# DIRECT-MONEY-02 — Direct sale canonical money boundary

ROOT_CAUSE: `CatalogRepository` normalizes catalog prices to BRL and marks the local `Product.price_currency` as BRL. `DirectCheckoutViewModel` therefore holds the authoritative UI/base estimate as BRL. `CheckoutActivity` then passed that BRL number to the payment selector while declaring the company base currency (PYG after CURRENCY-AUTH-01). The selector treated 6.60 as PYG, and PYG zero-decimal rounding produced 7. The receipt fallback also used the UI total when no frozen snapshot existed for cash.

CATALOG_SELLING_PRICE_RAW: 6,960 PYG in the server example; normalized local catalog value is 6.00.

CATALOG_SELLING_PRICE_CURRENCY: PYG at the server boundary; local normalized `Product.price_currency` is BRL.

VIEWMODEL_BASE_TOTAL_RAW: 6.00 BRL.

VIEWMODEL_FINAL_TOTAL_RAW: 6.60 BRL (6.00 + 0.60 tax).

CHECKOUT_DISPLAY_TOTAL: Gs. 7.656.

CHECKOUT_DISPLAY_CURRENCY: PYG.

PAYMENT_SELECTOR_INPUT: 6.60, the `finalTotal` base/catalog amount.

PAYMENT_SELECTOR_BASE_CURRENCY: BRL from the cart's explicit normalized catalog currency. Previously it was incorrectly read from global `CurrencyManager.getBaseCurrency()` (PYG).

TRANSACTION_CURRENCY: PYG.

QUOTE_TRANSACTION_AMOUNT: 7,656 PYG.

SALE_REQUEST_TOTAL: 7,656 PYG.

SALE_REQUEST_CURRENCY: PYG.

SALE_REQUEST_PAYMENT_CURRENCY: PYG.

SALE_REQUEST_CONVERTED_TOTAL: 6.60 BRL.

RECEIPT_TRANSACTION_AMOUNT: 7,656 PYG from the frozen `ReceiptMoneySnapshot` for both cash and PlugPay paths.

PYG_7656_TO_7_CAUSAL_CHAIN: 6.60 BRL was mislabeled as PYG; same-currency quoting retained 6.60, then `MoneyDecimal.roundToCurrency(..., PYG)` applied PYG's zero decimal places and rounded it to 7. The fixed boundary quotes 6.60 BRL to PYG once at rate 1,160, yielding 7,656.

ZERO_DECIMAL_ROUNDING_INVOLVED: YES.

WRONG_BASE_CURRENCY_DECLARATION: YES before; NO after.

WRONG_AMOUNT_PROVENANCE: YES before; NO after.

PRODUCT_PRICE_AUTHORITY: CatalogRepository's existing normalized catalog/base representation; no backend authority was changed.

PRODUCT_PRICE_IS_BASE_CURRENCY: YES.

PRODUCT_PRICE_IS_TRANSACTION_CURRENCY: NO.

PRODUCT_PRICE_CURRENCY_EXPLICIT_IN_MODEL: YES after catalog normalization (`price_currency=BRL`), with a fail-closed cart boundary for mixed currencies.

MONEY_BOUNDARY_FIXED: YES.

PAYMENT_AND_RECEIPT_INVARIANT: The payment quote, SaleRequest, frozen receipt snapshot, printed ticket and PDV1 QR use the same explicit transaction amount/currency. QR remains PDV1, with transaction-currency money fields; the existing 280×280, margin 4, EC M settings are preserved.

BACKEND MODIFIED: NO.

FINANCIAL AUTHORITY MODIFIED: NO.

NEW_LOCALIZATION_GAPS: 0. The localization audit reports only pre-existing findings (informational).

Tests: 956/956 PASS (478 debug + 478 release); targeted boundary/QR/currency tests 28/28 PASS.

assembleDebug: PASS.

Commit: pending.

PHYSICAL_VALIDATION: NOT_RUN_BY_CODEX.
