package com.plugpdv.pdv.hardware

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.pos.sdk.printer.POIPrinterManager
import com.pos.sdk.printer.models.BitmapPrintLine
import com.pos.sdk.printer.models.PrintLine
import com.pos.sdk.printer.models.TextPrintLine

class KozenPrinter(private val context: Context) : Printer {
    private var printerManager: POIPrinterManager? = null
    private var isBold = false
    private var alignment = PrintLine.LEFT
    private var fontSize = 24

    init {
        init()
    }

    override fun init() {
        try {
            printerManager = POIPrinterManager(context)
            printerManager?.open()
            printerManager?.cleanCache()
            printerManager?.setPrintGray(3000)
            Log.i(TAG, "KozenPrinter (com.pos.sdk) inicializado com sucesso.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init KozenPrinter (com.pos.sdk)", e)
        }
    }

    override fun printText(text: String) {
        val pm = printerManager ?: return
        try {
            pm.addPrintLine(TextPrintLine(text, alignment, fontSize, isBold))
        } catch (e: Exception) {
            Log.e(TAG, "Error adding text line", e)
        }
    }

    override fun printQRCode(data: String, size: Int) {
        Log.d(TAG, "printQRCode: \$data")
    }

    override fun printImage(bitmap: Bitmap) {
        val pm = printerManager ?: return
        try {
            pm.addPrintLine(BitmapPrintLine(bitmap, alignment))
        } catch (e: Exception) {
            Log.e(TAG, "Error adding bitmap line", e)
        }
    }

    override fun lineFeed(lines: Int) {
        val pm = printerManager ?: return
        for (i in 1..lines) {
            pm.addPrintLine(TextPrintLine(" ", PrintLine.LEFT, 16, false))
        }
    }

    override fun setBold(bold: Boolean) {
        this.isBold = bold
    }

    override fun setAlignment(alignment: Int) {
        this.alignment = when (alignment) {
            0 -> PrintLine.LEFT
            1 -> PrintLine.CENTER
            2 -> PrintLine.RIGHT
            else -> PrintLine.LEFT
        }
    }

    override fun setFontSize(size: Float) {
        this.fontSize = size.toInt()
    }

    override fun sendRaw(data: ByteArray) {
    }

    override fun reset() {
        printerManager?.cleanCache()
    }

    override fun isConnected(): Boolean = printerManager != null

    override fun getDeviceStatus(): Int = printerManager?.printerState ?: -1

    override fun getStatus(): String {
        val s = getDeviceStatus()
        return "Status \$s"
    }

    override fun close() {
        val pm = printerManager ?: return
        try {
            pm.beginPrint(object : POIPrinterManager.IPrinterListener {
                override fun onStart() {}
                override fun onFinish() {
                    try { pm.cleanCache() } catch (_: Exception) {}
                }
                override fun onError(code: Int, msg: String?) {
                    Log.e(TAG, "Printer error: \$msg (code: \$code)")
                    try { pm.cleanCache() } catch (_: Exception) {}
                }
            })
        } catch (e: Exception) {
            Log.e(TAG, "Exception during beginPrint", e)
        }
    }

    companion object {
        private const val TAG = "KozenPrinter"
    }
}
