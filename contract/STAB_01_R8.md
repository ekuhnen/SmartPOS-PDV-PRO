# STAB-01 — Release R8 / obfuscation baseline

Status: **APPROVED — release build, AAB and real-device smoke validated**  
Branch: `stabilize/play-console-2026`  
Baseline: `3a35af2`  
Implementation: `531cfc3`  
SLF4J R8 adjustment: `d6c46cf`

## Objective

Raise the Google Play release optimization/obfuscation baseline without changing payment, database, UI, edge-to-edge or orientation behavior.

## Changes

- enable R8 only for the `release` build type (`minifyEnabled true`);
- keep resource shrinking disabled for this first gate;
- add targeted ProGuard/R8 rules for Dspread, `com.action` printer classes, Kozen/Urovo `com.pos.sdk`, and Sunmi runtime/service surfaces;
- preserve runtime annotations/signatures needed by Retrofit/Gson/Kotlin;
- preserve application model field names used by Gson while still allowing application classes and executable code to be obfuscated;
- do not use blanket `-keep class com.plugpdv.pdv.**`;
- do not use broad `-ignorewarnings`;
- suppress only the known optional SLF4J 1.x `StaticLoggerBinder` reference instead of introducing a logging backend solely for R8.

## Explicitly out of scope

- Android 15 edge-to-edge;
- Android 16 orientation/resizing;
- Tap to Phone/Cielo;
- Room schema changes;
- payment state-machine changes;
- `shrinkResources`;
- release-signing secret rotation (separate stabilization gate).

## Validation completed

1. `testDebugUnitTest` PASS;
2. `assembleRelease` PASS with R8 enabled;
3. `bundleRelease` PASS;
4. release APK installed and opened successfully on real supported hardware;
5. login/capabilities PASS;
6. catalog/direct-sale/comanda navigation PASS;
7. barcode scanner PASS;
8. printer PASS;
9. offline/outbox recovery PASS;
10. low-value real PlugPay payment PASS, including callback/return into the PDV and no duplicate or spurious pending transaction.

STAB-01 is closed. Subsequent stabilization gates must preserve this release/R8 baseline.
