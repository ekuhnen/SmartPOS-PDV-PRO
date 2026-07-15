package com.plugpdv.pdv.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object ForceLogoutBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    fun emit(reason: String) {
        _events.tryEmit(reason)
    }
}
