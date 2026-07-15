package com.plugpdv.pdv.api

import com.plugpdv.pdv.utils.ForceLogoutBus
import okhttp3.Interceptor
import okhttp3.Response

class BlockResponseInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)
        
        if (response.code == 403) {
            val peek = response.peekBody(2048).string()
            if (peek.contains("DEVICE_BLOCKED") || peek.contains("USER_BLOCKED")) {
                val reason = if (peek.contains("DEVICE")) "Dispositivo bloqueado pelo administrador" else "Acesso revogado"
                ForceLogoutBus.emit(reason)
            }
        }
        
        return response
    }
}
