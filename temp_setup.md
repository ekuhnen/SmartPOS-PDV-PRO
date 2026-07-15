---
title: Set Up Integration
description: How to set up integration with Android Terminals
---

import { Callout } from 'nextra/components'

# Set Up Integration

## Development Environment

<Callout type="warning">
  **Good to know:** For development convenience, our terminals come in debug mode by default, with:

  * **ADB enabled**: ADB commands help developers install or debug apps, which is not secure for payment terminals. We disable ADB on release version devices for security, and apps can only be installed by OTA with TMS.
  * **Debug watermark**: This helps avoid developer misuse of development samples and production devices. The debug watermark will be removed on release production devices.
  * **Keystore**: The purpose of the keystore file is to prevent unknown apps from being installed on the device and breaking device security. You need to use the Dspread keystore file to sign your app to be able to install it on a debug device. For production devices, we will use your own keystore signature extracted from your signed APK, so that only you have permission to install apps on your device ([app.keystore](https://github.com/DspreadOrg/android/blob/ec9f6d055db6e0b1c2d5056960d558fcb19e9790/pos_android_studio_demo/pos_android_app/app.keystore)).
</Callout>

## Smart POS SDK

| Module | Download | Description |
| ------ | -------- | ----------- |
| Payment | [dspread_pos_sdk.jar](https://github.com/DspreadOrg/android/releases) | Handles card reading and pinpad functionalities |
| Printer | [dspread_print_sdk.aar](https://github.com/DspreadOrg/android/releases) | Prints receipts |
| Scanner | - | Refer to [scanner intent](/android-terminals/scanner-qr-bar-code) service |

Add the following code to your project's `build.gradle` file:

```gradle
plugins {
  id("com.android.application")
}

android { ... }

dependencies {
  implementation 'com.dspread.print:dspread_print_sdk:1.3.9-beta'
  implementation 'com.dspread.library:dspread_pos_sdk:7.0.7'
}
```

## Sample Code and Demo

* [Demo APK download](https://github.com/DspreadOrg/android/releases)
* [Demo source code](https://github.com/DspreadOrg/android)

## Demo App UI Preview

<div style={{ display: 'flex', justifyContent: 'space-between', marginTop: '1rem', flexWrap: 'wrap', gap: '1rem' }}>
  <div style={{ flex: '1', minWidth: '200px', textAlign: 'center' }}>
    <picture>
      <source srcSet="https://raw.githubusercontent.com/DspreadOrg/docs/main/public/images/webp/payment.webp 1x" type="image/webp" />
      <img src="https://raw.githubusercontent.com/DspreadOrg/docs/main/public/images/payment.png" alt="Payment Screen" style={{ maxWidth: '100%', height: 'auto', borderRadius: '0.5rem' }} />
    </picture>
    <p>Payment</p>
  </div>
  <div style={{ flex: '1', minWidth: '200px', textAlign: 'center' }}>
    <picture>
      <source srcSet="https://raw.githubusercontent.com/DspreadOrg/docs/main/public/images/webp/print.webp 1x" type="image/webp" />
      <img src="https://raw.githubusercontent.com/DspreadOrg/docs/main/public/images/print.png" alt="Print Screen" style={{ maxWidth: '100%', height: 'auto', borderRadius: '0.5rem' }} />
    </picture>
    <p>Print</p>
  </div>
  <div style={{ flex: '1', minWidth: '200px', textAlign: 'center' }}>
    <picture>
      <source srcSet="https://raw.githubusercontent.com/DspreadOrg/docs/main/public/images/webp/scanner.webp 1x" type="image/webp" />
      <img src="https://raw.githubusercontent.com/DspreadOrg/docs/main/public/images/scanner.png" alt="Scanner Screen" style={{ maxWidth: '100%', height: 'auto', borderRadius: '0.5rem' }} />
    </picture>
    <p>Scanner</p>
  </div>
</div>
