package com.plugpdv.pdv.hardware

import android.content.Context
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterException
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.SunmiPrinterService
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver

class SunmiScanner(private val context: Context) : Scanner {
    private var currentCallback: ScanCallback? = null
    private var isScanning = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (ACTION_DATA_CODE_RECEIVED == action) {
                val data = intent.getStringExtra(EXTRA_DATA)
                if (data != null) {
                    currentCallback?.onScanResult(data)
                }
                isScanning = false
            }
        }
    }

    override fun init() {
        // Sunmi scanner uses broadcasts, no specific init needed but mandated by interface
        Log.d("SunmiScanner", "Scanner initialized")
    }

    override fun startScan(callback: ScanCallback) {
        this.currentCallback = callback
        if (!isScanning) {
            val filter = IntentFilter()
            filter.addAction(ACTION_DATA_CODE_RECEIVED)
            context.registerReceiver(receiver, filter)
            
            val intent = Intent("com.sunmi.scanner.ACTION_SCAN_START")
            context.sendBroadcast(intent)
            isScanning = true
            Log.d(TAG, "Scan started")
        }
    }

    override fun stopScan() {
        if (isScanning) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Ignore
            }
            isScanning = false
            Log.d(TAG, "Scan stopped")
        }
    }

    companion object {
        private const val TAG = "SunmiScanner"
        private const val ACTION_DATA_CODE_RECEIVED = "com.sunmi.scanner.ACTION_DATA_CODE_RECEIVED"
        private const val EXTRA_DATA = "data"
    }
}
