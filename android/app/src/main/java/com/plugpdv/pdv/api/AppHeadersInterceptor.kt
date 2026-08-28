package com.plugpdv.pdv.api

import android.content.Context
import android.util.Log
import com.plugpdv.pdv.utils.DeviceIdProvider
import com.plugpdv.pdv.utils.ForceLogoutBus
import com.plugpdv.pdv.utils.ServerStateEvent
import okhttp3.Interceptor
import okhttp3.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Interceptor para injeção de cabeçalhos padrão da API v2 e detecção de desvio de relógio:
 * - X-App-Version: Versão da aplicação (BuildConfig.VERSION_NAME)
 * - X-Api-Version: Versão do contrato da API ("v2")
 * - X-Device-Id: ID único do dispositivo SmartPOS
 * - X-Idempotency-Key: Chave de idempotência (se não fornecida no request, é gerada quando aplicável)
 * 
 * Inspeciona o cabeçalho 'Date' ou 'X-Server-Time' da resposta do servidor para alertar
 * se a divergência do relógio do terminal for maior que 5 minutos (300.000 ms).
 */
class AppHeadersInterceptor(private val context: Context) : Interceptor {

    companion object {
        private const val TAG = "AppHeadersInterceptor"
        private const val CLOCK_DIVERGENCE_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutos

        private fun getAppVersionName(context: Context): String {
            return try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.5.3"
            } catch (e: Exception) {
                "1.5.3"
            }
        }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val appVersion = getAppVersionName(context)
        val requestBuilder = originalRequest.newBuilder()
            .header("X-App-Version", appVersion)
            .header("X-Api-Version", "1")
            .header("X-Device-Id", DeviceIdProvider.get(context))

        // Se o request já possui X-Idempotency-Key gerado no toque, ele é mantido.
        if (originalRequest.header("X-Idempotency-Key").isNull_or_Empty()) {
            val customIdempotency = originalRequest.header("Idempotency-Key")
            if (!customIdempotency.isNullOrEmpty()) {
                requestBuilder.header("X-Idempotency-Key", customIdempotency)
            }
        }

        val request = requestBuilder.build()
        val response = chain.proceed(request)

        checkClockDivergence(response)

        return response
    }

    private fun checkClockDivergence(response: Response) {
        try {
            val serverTimeHeader = response.header("X-Server-Time") ?: response.header("Date")
            if (serverTimeHeader.isNullOrEmpty()) return

            val serverTimeMs = parseServerTime(serverTimeHeader) ?: return
            val clientTimeMs = System.currentTimeMillis()
            val diffMs = abs(clientTimeMs - serverTimeMs)

            if (diffMs > CLOCK_DIVERGENCE_THRESHOLD_MS) {
                val diffMinutes = diffMs / 60000L
                Log.w(TAG, "ALERTA DE DIVERGÊNCIA DE RELÓGIO: Relógio local difere em $diffMinutes min do servidor.")
                ForceLogoutBus.emitServerState(
                    ServerStateEvent.ClockDivergenceDetected(diffMinutes = diffMinutes)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao verificar divergência de relógio: ${e.message}")
        }
    }

    private fun parseServerTime(headerValue: String): Long? {
        return try {
            // Tenta formato millis numérico em string
            headerValue.toLongOrNull() ?: run {
                // Tenta formato HTTP Date (RFC 1123)
                val sdf = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                sdf.timeZone = TimeZone.getTimeZone("GMT")
                sdf.parse(headerValue)?.time
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun String?.isNull_or_Empty(): Boolean = this.isNullOrEmpty()
}
