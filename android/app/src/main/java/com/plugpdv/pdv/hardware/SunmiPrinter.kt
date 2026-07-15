package com.plugpdv.pdv.hardware

import android.content.Context
import android.graphics.Bitmap
import android.os.RemoteException
import android.util.Log
import com.sunmi.peripheral.printer.InnerPrinterCallback
import com.sunmi.peripheral.printer.InnerPrinterException
import com.sunmi.peripheral.printer.InnerPrinterManager
import com.sunmi.peripheral.printer.SunmiPrinterService

class SunmiPrinter(private val context: Context) : Printer {
    private var sunmiPrinterService: SunmiPrinterService? = null
    private val pendingCommands = mutableListOf<(SunmiPrinterService) -> Unit>()

    private val innerPrinterCallback = object : InnerPrinterCallback() {
        override fun onConnected(service: SunmiPrinterService) {
            sunmiPrinterService = service
            Log.d(TAG, "Sunmi Printer Service connected via SDK")
            
            val commands = pendingCommands.toList()
            pendingCommands.clear()
            commands.forEach { 
                try {
                    it(service)
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing queued command", e)
                }
            }
        }

        override fun onDisconnected() {
            sunmiPrinterService = null
            Log.d(TAG, "Sunmi Printer Service disconnected")
        }
    }

    init {
        init()
    }

    private fun queueOrExecute(action: (SunmiPrinterService) -> Unit) {
        val service = sunmiPrinterService
        if (service != null) {
            action(service)
        } else {
            Log.w(TAG, "Printer service not connected yet. Queueing command.")
            pendingCommands.add(action)
            init()
        }
    }

    override fun init() {
        if (sunmiPrinterService != null) return
        try {
            InnerPrinterManager.getInstance().bindService(context, innerPrinterCallback)
        } catch (e: InnerPrinterException) {
            Log.e(TAG, "Error binding Sunmi Printer Service", e)
        }
    }

    override fun printText(text: String) {
        queueOrExecute { service ->
            try {
                service.printText(text, null)
            } catch (e: RemoteException) {
                Log.e(TAG, "Error printing text", e)
            }
        }
    }

    override fun printQRCode(data: String, size: Int) {
        queueOrExecute { service ->
            try {
                service.printQRCode(data, size, 0, null)
            } catch (e: RemoteException) {
                Log.e(TAG, "Error printing QR Code", e)
            }
        }
    }

    override fun printImage(bitmap: Bitmap) {
        queueOrExecute { service ->
            try {
                service.printBitmap(bitmap, null)
            } catch (e: RemoteException) {
                Log.e(TAG, "Error printing image", e)
            }
        }
    }

    override fun lineFeed(lines: Int) {
        queueOrExecute { service ->
            try {
                service.lineWrap(lines, null)
            } catch (e: RemoteException) {
                Log.e(TAG, "Error performing line feed", e)
            }
        }
    }

    override fun setBold(bold: Boolean) {
        queueOrExecute { service ->
            try {
                service.sendRAWData(byteArrayOf(0x1B, 0x45, (if (bold) 1 else 0).toByte()), null)
            } catch (e: RemoteException) {
                Log.e(TAG, "Error setting bold style", e)
            }
        }
    }

    override fun setAlignment(alignment: Int) {
        queueOrExecute { service ->
            try {
                service.setAlignment(alignment, null)
            } catch (e: RemoteException) {
                Log.e(TAG, "Error setting alignment", e)
            }
        }
    }

    override fun setFontSize(size: Float) {
        queueOrExecute { service ->
            try {
                service.setFontSize(size, null)
            } catch (e: RemoteException) {
                Log.e(TAG, "Error setting font size", e)
            }
        }
    }

    override fun sendRaw(data: ByteArray) {
        queueOrExecute { service ->
            try {
                service.sendRAWData(data, null)
            } catch (e: RemoteException) {
                Log.e(TAG, "Error sending RAW data", e)
            }
        }
    }

    override fun reset() {
        queueOrExecute { service ->
            try {
                service.printerInit(null)
                service.sendRAWData(byteArrayOf(0x1B, 0x56, 0x00), null)
                service.sendRAWData(byteArrayOf(0x1C, 0x2E), null)
                service.setAlignment(0, null)
                service.setFontSize(24f, null)
            } catch (e: RemoteException) {
                Log.e(TAG, "Error resetting printer", e)
            }
        }
    }

    override fun isConnected(): Boolean = sunmiPrinterService != null

    override fun getDeviceStatus(): Int {
        val service = sunmiPrinterService ?: return -1
        return try {
            service.updatePrinterState()
        } catch (e: RemoteException) {
            Log.e(TAG, "Error getting printer state", e)
            -1
        }
    }

    override fun getStatus(): String {
        val state = getDeviceStatus()
        return when (state) {
            1 -> "Normal"
            2 -> "Preparing"
            3 -> "Comms Error"
            4 -> "Out of Paper"
            5 -> "Overheated"
            6 -> "Cover Open"
            505 -> "No Printer"
            else -> "Error $state"
        }
    }

    override fun close() {}

    companion object {
        private const val TAG = "SunmiPrinter"
    }
}
