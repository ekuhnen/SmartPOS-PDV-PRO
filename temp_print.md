---
title: Print Receipt
description: How to print receipts
---

import { Callout } from 'nextra/components'


# Print Receipt

<Callout type="warning">
  **Download printr sdk api document:** 
  
📄 [**printr sdk api** (PDF)](https://github.com/DspreadOrg/android/blob/ec9f6d055db6e0b1c2d5056960d558fcb19e9790/QPOS-Android-PrinterSDK-Userguid-en-detail.pdf)


</Callout>

Add following code to project `build.gradle` file:

```java
dependencies {
    implementation 'com.dspread.print:dspread_print_sdk:1.3.9-beta'
}
```

## Overview
This activity (`PrintTicketActivity`) handles receipt printing for a POS (Point of Sale) system. It generates a receipt bitmap and sends it to a printer device.

## How the Printer Works

### 1. **Starting the Print Process**

The activity is launched with transaction data:

```java
Intent intent = new Intent(context, PrintTicketActivity.class);
intent.putExtra("terAmount", amount);
intent.putExtra("maskedPAN", maskedPAN);
intent.putExtra("terminalTime", terminalTime);
intent.putExtra("transactionTime", transactionTime);
startActivity(intent);
```

### 2. **Receipt Generation Flow**

1. **Data Collection**: Transaction details are collected (amount, card PAN, timestamps)
2. **Bitmap Generation**: A receipt image is created in the background
3. **Display/Print**: The receipt is either displayed on screen or auto-printed (depending on device size)

### 3. **Printing Options**

#### For Normal Devices:
- Receipt preview is shown
- User clicks **"Print Ticket"** button to print
- Triggers: `viewModel.printTicket(mBitmap)`

#### For Small Devices (320x240 or smaller):
- Auto-prints without preview
- Shows "Please Wait..." message
- User can retry with **"Small Print"** button

### 4. **Print Execution**

```java
// Triggered by button click
viewModel.printTicket(mBitmap);
```

The actual printing is handled by:
- `PrintTicketViewModel` which likely calls the printer device SDK
- Inherits from `PrinterBaseActivity` which manages the printer communication
- Uses the `PrinterDevice` class from the Dspread SDK

### 5. **Print Results**

The `onReturnPrintResult()` method handles printer feedback:

```java
@Override
protected void onReturnPrintResult(boolean isSuccess, String status, PrinterDevice.ResultType resultType)
```

**Success Cases**:
- Shows "Print Successful" dialog
- Navigates back to MainActivity after 3 seconds

**Failure Cases**:
- `NOPAPER`: No paper in printer
- `LOWERBATTERY`: Battery too low
- `OVERHEATING`: Printer overheated
- Unknown errors

## Key Features

### Animation
When printing on normal devices, the receipt image animates upward (simulating paper feeding):

```java
startPrintAnimation(); // Moves receipt image upward with fade effect
```

### Resource Management
Bitmaps are properly cleaned up to prevent memory leaks:

```java
@Override
protected void onDestroy() {
    if (mBitmap != null && !mBitmap.isRecycled()) {
        mBitmap.recycle();
        mBitmap = null;
    }
}
```

### Error Handling
- Checks if bitmap is ready before printing
- Regenerates receipt if bitmap is null or recycled
- Disables print buttons during printing to prevent duplicate requests

## Usage Summary

1. **Launch Activity** with transaction data
2. **Wait** for receipt generation (shows loading indicator)
3. **Preview** receipt (on normal screens)
4. **Click Print Button** to send to printer
5. **Handle Result** (success dialog or error with retry option)

The printer hardware communication is abstracted through the `PrinterDevice` SDK (likely from Dspread), so you don't need to handle low-level printer commands directly.
