package com.plugpdv.pdv.api

import android.util.Log
import com.plugpdv.pdv.utils.ForceLogoutBus
import com.plugpdv.pdv.utils.ServerStateEvent
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Trata centralizadamente a família de estados dirigidos pelo servidor:
 * - 403 DEVICE_BLOCKED: Bloqueio do dispositivo por admin
 * - 403 device_not_registered: Terminal não cadastrado
 * - 400 device_id_required: Falha estrutural de envio de device_id (bug diagnosticado)
 * - 426 upgrade_required: Força In-App Update imediato
 * - 503 Service Unavailable: Indisponibilidade temporária de banco/serviço (RETENTÁVEL via Outbox, não bloqueia)
 */
class BlockResponseInterceptor : Interceptor {

    companion object {
        private const val TAG = "BlockResponseInterceptor"
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        val code = response.code

        when (code) {
            403 -> {
                val peek = response.peekBody(2048).string()
                if (peek.contains("DEVICE_BLOCKED", ignoreCase = true) || peek.contains("USER_BLOCKED", ignoreCase = true)) {
                    val reason = if (peek.contains("DEVICE", ignoreCase = true)) {
                        "Dispositivo bloqueado pelo administrador"
                    } else {
                        "Acesso revogado"
                    }
                    Log.w(TAG, "Recebido 403 Bloqueado: $reason")
                    ForceLogoutBus.emitServerState(ServerStateEvent.DeviceBlocked(reason))
                } else if (peek.contains("device_not_registered", ignoreCase = true) || peek.contains("NOT_REGISTERED", ignoreCase = true)) {
                    Log.w(TAG, "Recebido 403: device_not_registered")
                    ForceLogoutBus.emitServerState(
                        ServerStateEvent.DeviceNotRegistered("Este terminal não está registrado. Chame o suporte.")
                    )
                }
            }

            400 -> {
                val peek = response.peekBody(2048).string()
                if (peek.contains("device_id_required", ignoreCase = true)) {
                    Log.e(TAG, "CRITICAL BUG: Servidor respondeu 400 device_id_required na rota ${request.url}. O app sempre deve enviar device_id.")
                    ForceLogoutBus.emitServerState(
                        ServerStateEvent.DeviceIdRequiredBug("Erro de identificação do terminal. Chame o suporte.")
                    )
                }
            }

            426 -> {
                Log.w(TAG, "Recebido 426 upgrade_required. Disparando atualização imediata.")
                ForceLogoutBus.emitServerState(
                    ServerStateEvent.UpgradeRequired("Este terminal precisa ser atualizado.")
                )
            }

            503 -> {
                // 503 é RETENTÁVEL. NÃO bloqueia o terminal. A operação vai para a outbox.
                Log.i(TAG, "Recebido 503 Service Unavailable na rota ${request.url}. Operação marcada como retentável para a Outbox.")
                ForceLogoutBus.emitServerState(
                    ServerStateEvent.ServiceUnavailableHiccup("Servidor temporariamente indisponível. As operações continuarão na fila local.")
                )
            }
        }

        return response
    }
}
