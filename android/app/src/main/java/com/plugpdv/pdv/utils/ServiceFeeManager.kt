package com.plugpdv.pdv.utils

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.plugpdv.pdv.models.ServiceFeeConfig

object ServiceFeeManager {
    private const val PREF_NAME = "service_fee_prefs"
    private const val KEY_CONFIG = "service_fee_config"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    fun saveConfig(context: Context, config: ServiceFeeConfig?) {
        val prefs = getPrefs(context)
        if (config == null) {
            prefs.edit().remove(KEY_CONFIG).apply()
        } else {
            val json = Gson().toJson(config)
            prefs.edit().putString(KEY_CONFIG, json).apply()
        }
    }

    fun getConfig(context: Context): ServiceFeeConfig? {
        val json = getPrefs(context).getString(KEY_CONFIG, null)
        return if (json != null) {
            try {
                Gson().fromJson(json, ServiceFeeConfig::class.java)
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }
}
