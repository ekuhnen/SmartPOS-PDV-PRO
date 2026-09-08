# MONEY-UI-01 — Comanda canonical currency display

## Provenance and verified cause

`ComandaDetailResponse.total` deserializes `total_comanda` (alias `total`).
Its explicit currency is `ComandaDetailResponse.baseCurrency` (`base_currency`).
`CommandViewModel.readComanda()` publishes the server response unchanged through
`comanda`. Previously, the items observer invoked `CommandOrderActivity.updateTotal()`,
which read that total and called `CurrencyManager.format(total)`.

`format` calls `convert`, then `fromBrl`, which multiplies by the selected currency's
BRL-relative rate. With selectedCurrency PYG and rate 1160, a total already in PYG
was treated as BRL: **43,384 × 1,160 = 50,325,440**. This confirms the code defect;
the physical P8 session was not reproduced by Codex. The old path has no general
guarantee that refreshing repairs its currency semantics.

## Change

The comanda observer now refreshes the footer directly. `CommandTotalDisplay`
passes the response's total and base currency to the injected
`CurrencyRulesProvider.formatExplicit`. Neither exchange rates nor global currency
selection reinterpret the total. Absent detail/currency displays an em dash.
The existing wire DTO remains unchanged; this patch introduces no new monetary
calculation or monetary storage representation.

The existing add-item flow updates only `items`, then reads fresh detail from the
server. The footer retains the last canonical total during that optimistic update.
The test checks the real ViewModel at the mutation API boundary before returning
the response, then checks the refreshed state after changing selectedCurrency.

Symbols, separators and displayed decimals follow the supplied currency rules.
With the test capabilities: PYG 43,384, R$ 100.00, USD 10. Existing provider defaults
may render the same PYG amount as Gs. 43.384; this patch does not author new country
rules or alter capabilities loading.

## Safety scope

- CANONICAL_MONEY_PASSED_TO_IMPLICIT_FX_FORMAT: 0 in the corrected footer path.
- CLIENT_TOTAL_RECALCULATION_ADDED: 0.
- BACKEND_CHANGES: 0; CONTRACT_CHANGES: 0; ROOM_SCHEMA_CHANGES: 0.
- FINANCIAL_AUTHORITY_MODIFIED: NO; PAYMENT_CALCULATIONS_MODIFIED: NO.
- FX_AUTHORITY_MODIFIED: NO; RC_GENERATED: NO.
- Existing workspace changes were excluded from this patch.
- Checkout, pay-by-items, tax, service fee, cash rounding, receipts, recovery,
  discovery, multi-comanda and realtime implementation were not changed.

## Validation

`gradlew.bat test assembleDebug --continue --console=plain`: BUILD SUCCESSFUL.
Debug: 437/437 PASS; release: 437/437 PASS (874 executions, zero failures,
errors or skips). The new class passes all 7 tests in each variant.
assembleDebug: PASS. `git diff --check` on the changed activity: PASS.
The new tests cover PYG, rate 1160, BRL, USD, missing currency, device locale
independence, and real ViewModel optimistic-add/refresh currency semantics.

Localization audit consistency passes and currency violations are zero. Global
localization coverage fails existing requirements: en has 220 missing keys and gn
has 298 missing keys (pt/es: 435/435). No translated resource was added or changed.

PHYSICAL_VALIDATION: NOT_RUN_BY_CODEX. Device instrumentation is not included in
the JVM suite results; no connected-device test or installation was performed.

The currency-display correction passes automated validation. The overall requested
gate remains FAIL because global localization coverage is incomplete; this report
does not relabel those existing gaps as PASS.
