# PILOT-STAB-02A — Localization Coverage Audit Baseline

**Generated At:** 2026-09-01T20:23:30Z  
**Git Branch:** `main`  
**Git Commit:** `9950309379e5`  
**Generator Version:** `1.1`

## Audit Scope and Verdict

This is a reproducible static localization baseline. It is best-effort static discovery; a physical/runtime walkthrough remains required.

All configured scan targets were processed successfully.

`PILOT-STAB-02A: PASS`

PASS describes audit execution and internal consistency. P0/P1 findings remain remediation work for STAB-02B/02C.

## Resource Coverage

| Locale | Total base keys | Translated keys | Missing keys | Extra keys | Coverage |
|---|---:|---:|---:|---:|---:|
| PT | 134 | 134 | 0 | 0 | 100.00% |
| ES | 134 | 112 | 22 | 0 | 83.58% |
| EN | 134 | 112 | 22 | 0 | 83.58% |
| GN | 134 | 34 | 100 | 0 | 25.37% |

## Missing Spanish Keys

**Count:** 22

```text
add_observation
add_to_table
close_table
current_order
customer_name
item_removed
observation_label
open_table
reason_required
removal_reason
select_destination
tab_comanda
tab_mesa
tab_venda_rapida
table_available
table_error
table_number
table_occupied
table_reserved
transfer_success
transfer_table
update_table
```

## Suspicious Spanish Values Identical to Portuguese

**Count:** 0

None detected by the conservative heuristic.

## Placeholder Validation

**Mismatches:** 0

Supported forms include `%s`, `%d`, `%f`, `%1$s`, and `%2$d`.

## Confirmed Finding Summary

- Hardcoded XML: 114
- Hardcoded Kotlin: 134
- Hardcoded Java: 3
- Hardcoded code total: 137
- ViewModel: 45
- Printing: 7
- Currency: 18
- Locale propagation confirmed: 0
- Review required: 8

## Severity

| Severity | Count |
|---|---:|
| P0-L10N | 18 |
| P1-L10N | 185 |
| P2-L10N | 48 |
| P3-L10N | 144 |
| Total unique confirmed findings | 395 |

Severity totals are calculated once from canonical finding IDs. Category arrays may reference the same ID without increasing the severity total.

## Printing Breakdown

| Target | Scanned | Confirmed | Review required | Resource usage | Receipt | Operator error | Logs/debug excluded |
|---|---|---:|---:|---|---:|---:|---:|
| PrinterHelper | yes | 0 | 0 | yes | 0 | 0 | 0 |
| PrinterUtil8 | yes | 3 | 0 | yes | 0 | 3 | 2 |
| GeneralPrinterUtil | yes | 0 | 0 | yes | 0 | 0 | 2 |
| SunmiPrinter | yes | 0 | 0 | no | 0 | 0 | 15 |
| GertecPrinter | yes | 0 | 0 | no | 0 | 0 | 4 |
| DejavooPrinter | yes | 0 | 0 | no | 0 | 0 | 11 |
| DspreadPrinter | yes | 4 | 3 | no | 0 | 4 | 15 |
| KozenPrinter | yes | 0 | 0 | no | 0 | 0 | 7 |
| ReceiptData | yes | 0 | 0 | no | 0 | 0 | 0 |

## Review Required

These entries are excluded from confirmed counts and severity arithmetic.

- `L10N-60387B3B761A` `android/app/src/main/java/com/plugpdv/pdv/hardware/DspreadPrinter.kt` — Calling printer.print(context)...: Technical/debug toast is operator-visible, but localization intent requires manual confirmation
- `L10N-B4BCFADA7C00` `android/app/src/main/java/com/plugpdv/pdv/hardware/DspreadPrinter.kt` — Printer class: ${printerDevice?.javaClass?.simpleName}: Technical/debug toast is operator-visible, but localization intent requires manual confirmation
- `L10N-61A16C7D9DA4` `android/app/src/main/java/com/plugpdv/pdv/hardware/DspreadPrinter.kt` — Dspread SDK Init Síncrono Concluído!: Technical/debug toast is operator-visible, but localization intent requires manual confirmation
- `L10N-9ABF04BFB916` `android/app/src/main/java/com/plugpdv/pdv/service/MyFirebaseMessagingService.kt` — MyFirebaseMessagingService: Presentation-capable context uses resources without demonstrable localized context
- `L10N-90A92B13A779` `android/app/src/main/java/com/plugpdv/pdv/ui/sale/PaymentMethodSelectorBottomSheet.kt` — PaymentMethodSelectorBottomSheet: Presentation-capable context uses resources without demonstrable localized context
- `L10N-36DCA11083B1` `android/app/src/main/java/com/plugpdv/pdv/ui/sale/TableHistoryBottomSheet.kt` — TableHistoryBottomSheet: Presentation-capable context uses resources without demonstrable localized context
- `L10N-615407905354` `android/app/src/main/java/com/plugpdv/pdv/ui/sale/UndeterminedPaymentBottomSheet.kt` — UndeterminedPaymentBottomSheet: Presentation-capable context uses resources without demonstrable localized context
- `L10N-C72B0FBBB11F` `android/app/src/main/java/com/plugpdv/pdv/utils/CrashReportActivity.kt` — CrashReportActivity: Activity does not inherit BaseActivity; inheritance alone does not prove missing localization context

## Known Limitations

- Best-effort static discovery; physical/runtime walkthrough remains required.
- Single-line sink heuristics can miss literals assembled across multiple lines or indirect presentation paths.
- REVIEW_REQUIRED findings are excluded from confirmed counts and severity totals.
- All configured scan targets were processed when missing_scan_targets is empty.

## Detailed Findings

The complete canonical findings and category views are available in `localization_audit.json`.

## Baseline Verdict

`PILOT-STAB-02A: PASS`
