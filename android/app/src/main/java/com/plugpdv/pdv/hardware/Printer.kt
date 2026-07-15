package com.plugpdv.pdv.hardware

interface Printer {
    fun init()
    fun printText(text: String)
    fun printQRCode(data: String, size: Int)
    fun printImage(bitmap: android.graphics.Bitmap)
    fun lineFeed(lines: Int)
    fun setBold(bold: Boolean)
    fun setAlignment(alignment: Int)
    fun setFontSize(size: Float)
    fun sendRaw(data: ByteArray)
    fun reset()
    fun isConnected(): Boolean
    fun getDeviceStatus(): Int
    fun getStatus(): String
    fun close()
}
