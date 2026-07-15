package com.plugpdv.pdv.hardware

interface ScanCallback {
    fun onScanResult(result: String)
    fun onScanFailed(error: String)
}
