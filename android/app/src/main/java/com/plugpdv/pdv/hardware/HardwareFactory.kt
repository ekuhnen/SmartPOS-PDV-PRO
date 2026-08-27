package com.plugpdv.pdv.hardware

import android.content.Context
import android.os.Build
import android.util.Log

object HardwareFactory {
    private const val TAG = "HardwareFactory"
    
    const val MANUFACTURER_SUNMI = "SUNMI"
    const val MANUFACTURER_GERTEC = "GERTEC"
    const val MANUFACTURER_DEJAVOO = "DEJAVOO"
    const val MANUFACTURER_DSPREAD = "DSPREAD"

    @Volatile
    private var printerInstance: Printer? = null
    @Volatile
    private var scannerInstance: Scanner? = null

    @JvmStatic
    @Synchronized
    fun getPrinter(context: Context): Printer? {
        if (printerInstance != null) {
            return printerInstance
        }

        val manufacturer = android.os.Build.MANUFACTURER.uppercase()
        val model = android.os.Build.MODEL.uppercase()
        val brand = android.os.Build.BRAND.uppercase()
        val debugInfo = "MFG: $manufacturer\nMODEL: $model\nBRAND: $brand"
        Log.d(TAG, "Detecting printer for: $debugInfo")

        fun showDebugToast(msg: String) {
            Log.d(TAG, "[Printer Detection Debug] $msg")
        }

        // Toast sempre visível com dados brutos do aparelho para diagnóstico
        showDebugToast("=== DETECÇÃO DE IMPRESSORA ===\n$debugInfo")


        printerInstance = when {
            manufacturer.contains(MANUFACTURER_SUNMI) || model.contains("SUNMI") -> {
                showDebugToast("SUNMI PRINTER DETECTADA\n$debugInfo")
                SunmiPrinter(context.applicationContext)
            }
            manufacturer.contains(MANUFACTURER_GERTEC) || model.contains("GERTEC") -> {
                showDebugToast("GERTEC PRINTER DETECTADA\n$debugInfo")
                try {
                    GertecPrinter(context.getApplicationContext())
                } catch (e: Throwable) {
                    Log.e(TAG, "Error initializing GertecPrinter", e)
                    showDebugToast("Erro GertecPrinter: ${e.message ?: e.toString()}")
                    null
                }
            }
            manufacturer.contains(MANUFACTURER_DEJAVOO) || model.contains("DEJAVOO") -> {
                showDebugToast("DEJAVOO PRINTER DETECTADA\n$debugInfo")
                DejavooPrinter(context.getApplicationContext())
            }
            manufacturer.contains(MANUFACTURER_DSPREAD) || manufacturer.contains("QPOS") || model.contains("D80") || model.contains("DSPREAD") || model.contains("QPOS") -> {
                Log.d(TAG, "Dspread device detected, initializing DspreadPrinter.")
                showDebugToast("DSPREAD PRINTER DETECTADA\n$debugInfo")
                try {
                    DspreadPrinter(context.applicationContext)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error initializing Dspread printer", e)
                    SunmiPrinter(context.applicationContext)
                }
            }
            manufacturer.contains("UROVO") || model.contains("UROVO") -> {
                Log.d(TAG, "Urovo device detected, initializing KozenPrinter (Urovo SDK).")
                showDebugToast("UROVO PRINTER DETECTADA\n$debugInfo")
                try {
                    KozenPrinter(context.applicationContext)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error initializing Urovo printer", e)
                    SunmiPrinter(context.applicationContext)
                }
            }
            manufacturer.contains("KOZEN") || manufacturer.contains("XIANGCHENG") || manufacturer.contains("SHANGHAI") || model.contains("KOZEN") || model.contains("P8") || brand.contains("KOZEN") -> {
                Log.d(TAG, "Kozen/P8 Neo device detected. Using ActionPrinter SDK.")
                showDebugToast("KOZEN P8 NEO DETECTADA\n$debugInfo")
                try {
                    KozenPrinter(context.applicationContext)
                } catch (e: Throwable) {
                    Log.e(TAG, "Erro ao inicializar KozenPrinter (ActionPrinter)", e)
                    showDebugToast("Erro KozenPrinter: ${e.message ?: e.toString()}")
                    SunmiPrinter(context.applicationContext)
                }
            }
            // Fallback: Tenta detectar se a classe do PrinterManager está disponível no sistema
            com.plugpdv.pdv.hardware.printer.PrinterUtil8.isImpressoraCompativel() -> {
                Log.d(TAG, "PrinterManager class detected, using KozenPrinter as fallback.")
                showDebugToast("FALLBACK: KOZEN (PrinterManager)\n$debugInfo")
                try {
                    KozenPrinter(context.applicationContext)
                } catch (e: Throwable) {
                    Log.e(TAG, "Error initializing Kozen printer in fallback", e)
                    showDebugToast("Erro fallback KozenPrinter: ${e.message ?: e.toString()}")
                    SunmiPrinter(context.applicationContext)
                }
            }
            else -> {
                Log.w(TAG, "No specific printer implementation for: $manufacturer. Using SunmiPrinter as default fallback.")
                showDebugToast("FALLBACK DEFAULT: SUNMI PRINTER\n$debugInfo")
                SunmiPrinter(context.applicationContext)
            }
        }
        
        return printerInstance
    }

    @JvmStatic
    @Synchronized
    fun getScanner(context: Context): Scanner? {
        if (scannerInstance != null) {
            return scannerInstance
        }

        val manufacturer = Build.MANUFACTURER.uppercase()
        Log.d(TAG, "Detecting manufacturer for Scanner: $manufacturer")

        scannerInstance = when {
            manufacturer.contains(MANUFACTURER_SUNMI) -> SunmiScanner(context.applicationContext)
            else -> {
                Log.w(TAG, "No specific scanner implementation for: $manufacturer")
                null
            }
        }
        
        return scannerInstance
    }
}
