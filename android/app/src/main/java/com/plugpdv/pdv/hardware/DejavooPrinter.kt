package com.plugpdv.pdv.hardware

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

class DejavooPrinter(private val context: Context) : Printer {

    init {
        init()
    }

    override fun init() {
        Log.d(TAG, "Dejavoo Printer Initialized (Placeholder)")
    }

    override fun printText(text: String) {
        Log.d(TAG, "Printing text on Dejavoo: $text")
    }

    override fun printQRCode(data: String, size: Int) {
        Log.d(TAG, "Printing QR Code on Dejavoo: $data")
    }

    override fun printImage(bitmap: Bitmap) {
        Log.d(TAG, "Printing Image on Dejavoo")
    }

    override fun lineFeed(lines: Int) {
        Log.d(TAG, "Line feed on Dejavoo: $lines")
    }

    override fun setBold(bold: Boolean) {
        Log.d(TAG, "Set Bold on Dejavoo: $bold")
    }

    override fun setAlignment(alignment: Int) {
        Log.d(TAG, "Set Alignment on Dejavoo: $alignment")
    }

    override fun setFontSize(size: Float) {
        Log.d(TAG, "Set Font Size on Dejavoo: $size")
    }

    override fun sendRaw(data: ByteArray) {
        Log.d(TAG, "Sending RAW data to Dejavoo: ${data.size} bytes")
    }

    override fun reset() {
        Log.d(TAG, "Resetting Dejavoo Printer")
    }

    override fun isConnected(): Boolean = true

    override fun getDeviceStatus(): Int = 1 // OK

    override fun getStatus(): String = "Dejavoo Connected (Mock)"

    override fun close() {
        Log.d(TAG, "Dejavoo Printer closed")
    }

    companion object {
        private const val TAG = "DejavooPrinter"
    }
}
