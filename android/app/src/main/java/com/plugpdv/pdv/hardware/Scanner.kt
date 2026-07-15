package com.plugpdv.pdv.hardware

interface Scanner {
    fun init()
    fun startScan(callback: ScanCallback)
    fun stopScan()
}
