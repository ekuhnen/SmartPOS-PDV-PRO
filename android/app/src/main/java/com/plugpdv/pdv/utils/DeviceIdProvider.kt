package com.plugpdv.pdv.utils

import android.content.Context
import java.util.UUID

object DeviceIdProvider {
    private const val PREF = "pdv_prefs"
    private const val KEY = "device_id"

    fun get(ctx: Context): String {
        val prefs = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return prefs.getString(KEY, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(KEY, it).apply()
        }
    }

    fun clear(ctx: Context) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().remove(KEY).apply()
    }
}
