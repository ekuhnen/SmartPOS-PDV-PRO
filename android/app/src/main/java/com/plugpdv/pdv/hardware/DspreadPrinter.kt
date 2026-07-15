package com.plugpdv.pdv.hardware

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.dspread.print.device.PrinterDevice
import com.dspread.print.device.PrinterManager
import com.dspread.print.device.PrintListener
import com.dspread.print.device.PrinterInitListener
import com.dspread.print.device.bean.PrintLineStyle
import com.dspread.print.widget.PrintLine
import com.action.printerservice.PrintStyle

class DspreadPrinter(private val context: Context) : Printer {
    private val TAG = "DspreadPrinter"
    private var printerDevice: PrinterDevice? = null
    
    private var isPrinterConnected = false
    private val pendingCommands = mutableListOf<() -> Unit>()

    private var isBold = false
    private var alignment = PrintLine.LEFT
    private var fontSize = 14
    private var isInitialized = false

    private fun showToast(msg: String) {
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
        }
    }

    private val printListener = object : PrintListener {
        override fun printResult(isSuccess: Boolean, status: String?, type: PrinterDevice.ResultType?) {
            val msg = "PrintResult: success=$isSuccess, status=$status, type=$type"
            Log.d(TAG, msg)
            showToast(msg)
        }
    }

    private val initListener = object : PrinterInitListener {
        override fun connected() {
            val msg = "Dspread Printer Service CONNECTED!"
            Log.d(TAG, msg)
            showToast(msg)
            isPrinterConnected = true
            
            val commands = pendingCommands.toList()
            pendingCommands.clear()
            commands.forEach { 
                try {
                    it()
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing queued command", e)
                }
            }
        }

        override fun disconnected() {
            val msg = "Dspread Printer Service DISCONNECTED"
            Log.d(TAG, msg)
            showToast(msg)
            isPrinterConnected = false
        }
    }

    init {
        init()
    }

    override fun init() {
        if (isInitialized) return
        try {
            // Com a SDK nova 1.9.4, o PrinterManager reconhece nativamente a D80 
            // e mapeia para a D60 (UART), então podemos usar o getPrinter() padrão.
            printerDevice = PrinterManager.getInstance().getPrinter()
            printerDevice?.setPrintListener(printListener)
            
            showToast("Printer class: ${printerDevice?.javaClass?.simpleName}")

            // Inicialização síncrona/assíncrona dependendo do modelo retornado
            printerDevice?.initPrinter(context)
            isPrinterConnected = true
            
            val commands = pendingCommands.toList()
            pendingCommands.clear()
            commands.forEach { 
                try {
                    it()
                } catch (e: Exception) {
                    Log.e(TAG, "Error executing queued command", e)
                }
            }
            
            isInitialized = true
            showToast("Dspread SDK Init Síncrono Concluído!")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize Dspread Printer", e)
            showToast("Init Exception: ${e.message}")
        }
    }

    private fun queueOrExecute(action: () -> Unit) {
        if (isPrinterConnected) {
            action()
        } else {
            Log.w(TAG, "Printer service not connected yet. Queueing command.")
            pendingCommands.add(action)
        }
    }

    private fun applyStyle() {
        val style = if (isBold) PrintStyle.FontStyle.BOLD else PrintStyle.FontStyle.NORMAL
        val lineStyle = PrintLineStyle(style, alignment, fontSize)
        printerDevice?.addPrintLintStyle(lineStyle)
    }

    override fun printText(text: String) {
        queueOrExecute {
            val printer = printerDevice ?: return@queueOrExecute
            try {
                applyStyle()
                val lines = text.split("\n")
                for (i in lines.indices) {
                    val line = lines[i]
                    if (i == lines.size - 1 && line.isEmpty()) {
                        break
                    }
                    if (line.isEmpty()) {
                        printer.addText(" ")
                    } else {
                        printer.addText(line)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding text", e)
                showToast("addText Error: ${e.message}")
            }
        }
    }

    override fun printQRCode(data: String, size: Int) {
        queueOrExecute {
            val printer = printerDevice ?: return@queueOrExecute
            try {
                val finalSize = if (size > 0) size else 300
                applyStyle()
                printer.addQRCode(finalSize, "QR_CODE", data, alignment)
            } catch (e: Exception) {
                Log.e(TAG, "Error adding QR code", e)
            }
        }
    }

    override fun printImage(bitmap: Bitmap) {
        queueOrExecute {
            val printer = printerDevice ?: return@queueOrExecute
            try {
                printer.addBitmap(bitmap)
            } catch (e: Exception) {
                Log.e(TAG, "Error adding image", e)
            }
        }
    }

    override fun lineFeed(lines: Int) {
        queueOrExecute {
            val printer = printerDevice ?: return@queueOrExecute
            try {
                printer.feedLines(lines)
            } catch (e: Exception) {
                Log.e(TAG, "Error adding line feed", e)
                try {
                    applyStyle()
                    for (i in 1..lines) {
                        printer.addText(" ")
                    }
                } catch (ex: Exception) {
                    Log.e(TAG, "Error in line feed fallback", ex)
                }
            }
        }
    }

    override fun setBold(bold: Boolean) {
        queueOrExecute {
            this.isBold = bold
        }
    }

    override fun setAlignment(align: Int) {
        queueOrExecute {
            this.alignment = when (align) {
                0 -> PrintLine.LEFT
                1 -> PrintLine.CENTER
                2 -> PrintLine.RIGHT
                else -> PrintLine.LEFT
            }
        }
    }

    override fun setFontSize(size: Float) {
        queueOrExecute {
            this.fontSize = Math.round(size * 0.58f).coerceAtLeast(1)
        }
    }

    override fun sendRaw(data: ByteArray) {
        Log.w(TAG, "sendRaw is not supported on DspreadPrinter")
    }

    override fun reset() {
        queueOrExecute {
            isBold = false
            alignment = PrintLine.LEFT
            fontSize = 14
        }
    }

    override fun isConnected(): Boolean {
        return isPrinterConnected && printerDevice != null
    }

    override fun getDeviceStatus(): Int {
        return if (isPrinterConnected) 0 else -1
    }

    override fun getStatus(): String {
        return if (isPrinterConnected) "Connected" else "Disconnected"
    }

    override fun close() {
        queueOrExecute {
            val printer = printerDevice ?: return@queueOrExecute
            try {
                showToast("Calling printer.print(context)...")
                printer.print(context)
            } catch (e: Exception) {
                Log.e(TAG, "Error triggering print", e)
                showToast("print() Exception: ${e.message}")
            }
        }
        
        // Timeout check para caso não conecte:
        if (!isPrinterConnected) {
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isPrinterConnected) {
                    showToast("ERRO: O serviço de impressão não respondeu (connected() não foi chamado).")
                }
            }, 3000)
        }
    }
}
