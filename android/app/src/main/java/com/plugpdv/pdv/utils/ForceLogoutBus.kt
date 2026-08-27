package com.plugpdv.pdv.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed class ServerStateEvent {
    data class DeviceBlocked(val reason: String = "Dispositivo bloqueado pelo administrador") : ServerStateEvent()
    data class DeviceNotRegistered(val message: String = "Este terminal não está registrado. Chame o suporte.") : ServerStateEvent()
    data class DeviceIdRequiredBug(val message: String = "Erro de identificação do terminal. Chame o suporte.") : ServerStateEvent()
    data class UpgradeRequired(val message: String = "Este terminal precisa ser atualizado.") : ServerStateEvent()
    data class ServiceUnavailableHiccup(val message: String = "Servidor indisponível momentaneamente. As operações continuam na fila local.") : ServerStateEvent()
    data class ClockDivergenceDetected(val diffMinutes: Long, val message: String = "Divergência no relógio do dispositivo ($diffMinutes min). Ajuste a hora do terminal.") : ServerStateEvent()
}

object ForceLogoutBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val events = _events.asSharedFlow()

    private val _serverStateEvents = MutableSharedFlow<ServerStateEvent>(extraBufferCapacity = 5)
    val serverStateEvents = _serverStateEvents.asSharedFlow()

    fun emit(reason: String) {
        _events.tryEmit(reason)
    }

    fun emitServerState(event: ServerStateEvent) {
        _serverStateEvents.tryEmit(event)
        when (event) {
            is ServerStateEvent.DeviceBlocked -> _events.tryEmit(event.reason)
            is ServerStateEvent.DeviceNotRegistered -> _events.tryEmit(event.message)
            else -> {}
        }
    }
}
