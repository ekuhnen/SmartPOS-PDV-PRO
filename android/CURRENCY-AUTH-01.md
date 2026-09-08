# CURRENCY-AUTH-01 — Terminal currency authority alignment

## Audit result

Before this change, `BaseActivity.showCurrencySelector()` read
`CurrencyManager.getAvailableCurrencies()`. That list came from the cached or
fresh `ExchangeResponse.moedas` returned by `POST api-cambio`. The Retrofit
`GET api/v2/terminal/capabilities` method and `CapabilitiesResponse.currencies`
already existed, but no login or selector code consumed them. Therefore FX-rate
availability was being used as the apparent authorization list.

`SELECTOR_AUTHORITY_BEFORE: api-cambio moedas + legacy BRL-initialized manager`.
`LOGIN_CURRENCY_ENDPOINT: POST api-cambio (background refresh)`.
`CAPABILITIES_USED_BEFORE: NO`.
`CACHE_USED: YES` (`currency_prefs/exchange_rates`).
`DEFAULT_BRL_FALLBACK_BEFORE: YES` through `CurrencyManager.selectedCurrency = BRL`
and `getBaseCurrency()` when rates were absent.
`CURRENCY_AUTHORITY_MIXED_WITH_FX: YES`.

## Authority change

`CurrencyManager` now has a tenant-scoped capability authorization state. Login
prepares the manager for the authenticated `owner_id`, refreshes `api-cambio`
for FX data, then fetches `api/v2/terminal/capabilities` before publishing login
success. Capability currency keys are the sole allow-list used by the selector.
The selector displays those keys directly, so PYG/USD produces exactly PYG/USD;
BRL is not synthesized from rates or defaults. `base_currency` is consumed when
present in the capability response; otherwise the synchronized FX base is used
only to choose an authorized default. For PYG/USD with base PYG, the initial
selection is PYG.

After an authenticated tenant is prepared, missing capabilities fail closed:
the selector has no currencies until a nonempty capabilities response is applied.
An FX synchronization failure does not replace the allow-list or add BRL. The
helper `isOperational(code)` distinguishes an authorized currency from one with
an unavailable FX rate: PYG remains operational while an authorized USD without
a required rate fails closed. Existing FX calculation methods and payment/cashier
payloads were not changed; `CashierRequest.moeda` remains the selected explicit
currency.

## Cache isolation

The currency preferences now persist `currency_owner_id`, authorized currency
codes and authorized base currency alongside rates. Loading requires the active
tenant to match that owner. `prepareForTenant()` drops in-memory rates,
authorization and selection when the authenticated owner differs, and rejects
a cache owned by another tenant. No unrelated app state is cleared.

The same manager state is used by BaseActivity's selector, Cashier, Venda Direta,
Mesa/Comanda checkout and payment method flows through `selectedCurrency`; no
per-screen currency list was added. Capabilities remain authorization; api-cambio
remains rate data.

## Required return values

```text
SELECTOR_AUTHORITY_AFTER: terminal capabilities currencies
CAPABILITIES_USED_AFTER: YES
FX_AUTHORITY: api-cambio rates only
AUTHORIZED_CURRENCY_AUTHORITY: api/v2/terminal/capabilities.currencies
CURRENCY_CACHE_TENANT_SCOPED_BEFORE: NO
CURRENCY_CACHE_TENANT_SCOPED_AFTER: YES
UNAUTHORIZED_BRL_FALLBACK_REMOVED: YES (after authenticated capabilities)
```

## Validation

`CurrencyAuthorityAlignmentTest` covers PYG/USD selector authority, BRL-only,
BRL/USD, PYG default, stale BRL cache replacement, different-tenant rejection,
rates not authorizing extra BRL, missing USD FX fail-closed with PYG operational,
CashierRequest PYG/USD semantics and FX-sync failure preservation. No production
credentials, passwords or tokens are used.

Full Android suite: debug 471/471 PASS; release 471/471 PASS; 942 executions
with zero failures, errors or skips. `assembleDebug`: PASS. Existing MONEY-UI-01,
DIRECT-QR-01, Mesa/Comanda and payment tests remain green. No resource files were
changed and no new localization gaps were introduced. Existing global localization
debt remains informational (en 220 missing keys, gn 298 missing keys).

`BACKEND MODIFIED: NO` · `CONTRACT MODIFIED: NO` · `FX CALCULATIONS MODIFIED: NO`
· `PAYMENT MODIFIED: NO` · `CASHIER ACCOUNTING MODIFIED: NO` · `ROOM MODIFIED: NO`
· `REALTIME MODIFIED: NO` · `RC GENERATED: NO`.

`PHYSICAL_VALIDATION: NOT_RUN_BY_CODEX`.
