# STAB-02A — Android 15/16 edge-to-edge baseline

Status: **CANDIDATE — requires build and visual regression on real device**  
Branch: `stabilize/play-console-2026`  
Depends on: STAB-01 approved

## Objective

Make Plug PDV explicitly edge-to-edge compatible for target SDK 36 without changing payment, Room, orientation policy or vendor SDK behavior.

Android 15 enforces edge-to-edge for apps targeting API 35+, and Android 16 removes the opt-out path. Existing XML screens therefore need deterministic system-bar and display-cutout inset handling.

## Changes

- call `enableEdgeToEdge()` from `BaseActivity` before `super.onCreate()` so Android versions below 15 exercise the same layout model;
- apply `systemBars + displayCutout` insets to all four sides of the decor content container;
- remove the legacy theme `android:statusBarColor` declaration;
- remove the theme-level `android:fitsSystemWindows` declaration;
- keep `android:windowLightStatusBar=true` for readable icons on the current light theme.

## Explicitly out of scope

- Material Components dependency upgrade;
- deprecated edge-to-edge calls inside Material Components;
- Dspread `InputPinActivity` window APIs;
- Android 16 orientation/resizability restrictions;
- any payment/provider logic;
- any database/schema change.

## Why vendor warnings may remain

Google Play can still report deprecated calls that are packaged inside third-party libraries. The screenshot currently identifies Material Components classes and `com.dspread.xpos.pinKeyboard.InputPinActivity`. STAB-02A fixes the app-owned window contract only. Material and Dspread are handled separately after this gate.

## Validation gate

Run:

```bat
cd android
gradlew.bat testDebugUnitTest
gradlew.bat assembleDebug
gradlew.bat assembleRelease
gradlew.bat bundleRelease
```

Then install the release APK and visually verify at least:

1. login screen — no controls hidden behind status/navigation bars;
2. operator dashboard;
3. direct sale/catalog list, including top and bottom actions;
4. checkout;
5. table/comanda screens;
6. dialogs/bottom sheets/snackbars;
7. barcode-scanner return flow;
8. PlugPay launch and callback return.

Where possible, test both gesture navigation and 3-button navigation. STAB-02A is approved only after build and visual regression pass.
