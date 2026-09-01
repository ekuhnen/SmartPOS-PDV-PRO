# PILOT-STAB-02A — Localization Coverage Audit Baseline

**Generated At:** 2026-09-01T21:37:47Z  
**Git Branch:** `pilot-stab/02b-spanish-ui`  
**Git Commit:** `57305e139962`  
**Generator Version:** `1.1`

## Audit Scope and Verdict

This is a reproducible static localization baseline. It is best-effort static discovery; a physical/runtime walkthrough remains required.

All configured scan targets were processed successfully.

`PILOT-STAB-02A: PASS`

PASS describes audit execution and internal consistency. P0/P1 findings remain remediation work for STAB-02B/02C.

## Resource Coverage

| Locale | Total base keys | Translated keys | Missing keys | Extra keys | Coverage |
|---|---:|---:|---:|---:|---:|
| PT | 310 | 310 | 0 | 0 | 100.00% |
| ES | 310 | 310 | 0 | 0 | 100.00% |
| EN | 310 | 112 | 198 | 0 | 36.13% |
| GN | 310 | 34 | 276 | 0 | 10.97% |

## Missing Spanish Keys

**Count:** 0

```text
```

## Suspicious Spanish Values Identical to Portuguese

**Count:** 0

None detected by the conservative heuristic.

## Placeholder Validation

**Mismatches:** 0

Supported forms include `%s`, `%d`, `%f`, `%1$s`, and `%2$d`.

## Confirmed Finding Summary

- Hardcoded XML: 17
- Hardcoded Kotlin: 10
- Hardcoded Java: 0
- Hardcoded code total: 10
- ViewModel: 6
- Printing: 0
- Currency: 17
- Locale propagation confirmed: 0
- Review required: 5

## Severity

| Severity | Count |
|---|---:|
| P0-L10N | 17 |
| P1-L10N | 10 |
| P2-L10N | 0 |
| P3-L10N | 474 |
| Total unique confirmed findings | 501 |

Severity totals are calculated once from canonical finding IDs. Category arrays may reference the same ID without increasing the severity total.

## Printing Breakdown

| Target | Scanned | Confirmed | Review required | Resource usage | Receipt | Operator error | Logs/debug excluded |
|---|---|---:|---:|---|---:|---:|---:|
| PrinterHelper | yes | 0 | 0 | yes | 0 | 0 | 0 |
| PrinterUtil8 | yes | 0 | 0 | yes | 0 | 0 | 2 |
| GeneralPrinterUtil | yes | 0 | 0 | yes | 0 | 0 | 2 |
| SunmiPrinter | yes | 0 | 0 | no | 0 | 0 | 15 |
| GertecPrinter | yes | 0 | 0 | no | 0 | 0 | 4 |
| DejavooPrinter | yes | 0 | 0 | no | 0 | 0 | 11 |
| DspreadPrinter | yes | 0 | 0 | yes | 0 | 0 | 18 |
| KozenPrinter | yes | 0 | 0 | no | 0 | 0 | 7 |
| ReceiptData | yes | 0 | 0 | no | 0 | 0 | 0 |

## Review Required

These entries are excluded from confirmed counts and severity arithmetic.

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
