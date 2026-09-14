# PLAY-STABILIZE-01 — Android 15/16 windowing baseline

Status: **CANDIDATE — requires local build and visual regression**  
Branch: `stabilize/play-console-android15-16`  
Base: `3a35af2` (PAYMENT-03B approved; Tap to Phone work frozen)

## Goal

Stabilize the current Plug PDV release against Google Play Android 15/16 windowing warnings without changing payment behavior, Room schema, provider policy, checkout, printer integrations, or Tap to Phone.

## Findings from Play Console

The currently published version reports:

1. edge-to-edge may not be available consistently;
2. deprecated window APIs/parameters are present (`setStatusBarColor`, `setNavigationBarColor`, cutout short-edges), including calls originating from Material Components and Dspread PIN activity;
3. orientation/resizability restrictions exist in first-party and third-party activities;
4. release optimization/obfuscation is low (2%).

## This gate

This gate addresses only the first-party edge-to-edge baseline:

- call `enableEdgeToEdge()` in the shared `BaseActivity` before `super.onCreate`;
- apply all system-bar + display-cutout insets to the content root using authored padding as baseline;
- apply the same safe-area behavior to `CrashReportActivity`, which does not inherit `BaseActivity`;
- remove legacy `android:statusBarColor` and `android:fitsSystemWindows` theme attributes.

## Explicitly not changed yet

- `android:screenOrientation` declarations;
- JourneyApps `CaptureActivity` orientation;
- Dspread `InputPinActivity` orientation or window behavior;
- Material Components version;
- Dspread SDK binaries;
- R8/minification/obfuscation;
- payment flow or PlugPay provider behavior;
- Tap to Phone providers or SDKs.

Third-party deprecated API warnings can remain after this gate because they originate inside bundled dependencies. They will be handled in later gates through dependency upgrades/vendor SDK review rather than unsafe manifest or bytecode overrides.

## Regression gate

Run:

```bat
cd android
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug
```

Then install the debug APK on one normal Android phone/SmartPOS and visually verify at minimum:

- Login;
- Operator dashboard;
- Direct sale;
- Table/comanda screen;
- Checkout;
- any BottomSheet used by the normal flow;
- PlugPay low-value payment and return callback.

Check that no tappable/content element is hidden behind the status bar, navigation bar, gesture area, or display cutout, and that there is no doubled top/bottom padding.

## Next gates

- PLAY-STABILIZE-02: dependency/window API audit (Material + Dspread), with no vendor override unless proven safe;
- PLAY-STABILIZE-03: Android 16 large-screen/orientation strategy and adaptive-layout regression;
- PLAY-STABILIZE-04: release R8/obfuscation hardening with SDK-specific keep rules and release regression.
