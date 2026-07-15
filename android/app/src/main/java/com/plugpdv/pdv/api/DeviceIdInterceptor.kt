package com.plugpdv.pdv.api

import android.content.Context
import com.plugpdv.pdv.utils.DeviceIdProvider
import okhttp3.Interceptor
import okhttp3.Response

class DeviceIdInterceptor(private val ctx: Context) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request().newBuilder()
            .header("X-Device-Id", DeviceIdProvider.get(ctx))
            .build()
        return chain.proceed(req)
    }
}
