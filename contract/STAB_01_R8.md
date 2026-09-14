# STAB-01 — Release R8 / obfuscation baseline

Status: **CANDIDATE — requires local release build and real-device smoke test**  
Branch: `stabilize/play-console-2026`  
Baseline: `3a35af2`

## Objective

Raise the Google Play release optimization/obfuscation baseline without changing payment, database, UI, edge-to-edge or orientation behavior.

## Changes

- enable R8 only for the `release` build type (`minifyEnabled true`);
- keep resource shrinking disabled for this first gate;
- add targeted ProGuard/R8 rules for Dspread, `com.action` printer classes, Kozen/Urovo `com.pos.sdk`, and Sunmi runtime/service surfaces;
- preserve runtime annotations/signatures needed by Retrofit/Gson/Kotlin;
- preserve application model field names used by Gson while still allowing application classes and executable code to be obfuscated;
- do not use blanket `-keep class com.plugpdv.pdv.**`;
- do not use broad `-ignorewarnings`.

## Explicitly out of scope

- Android 15 edge-to-edge;
- Android 16 orientation/resizing;
- Tap to Phone/Cielo;
- Room schema changes;
- payment state-machine changes;
- `shrinkResources`;
- release-signing secret rotation (separate stabilization gate).

## Validation gate

Run:

```bat
cd android
gradlew.bat testDebugUnitTest
gradlew.bat assembleRelease
gradlew.bat bundleRelease
```

If R8 fails, review the generated `missing_rules.txt` and add only the narrowest justified vendor rule. Do not suppress all warnings.

After release build succeeds, perform a release-build smoke test on real supported hardware covering login/capabilities, catalog, direct sale, comanda, barcode scanner, printer, offline/outbox recovery and one low-value real PlugPay payment with callback back into the PDV.

STAB-01 is approved only after both the release build and runtime smoke pass.
