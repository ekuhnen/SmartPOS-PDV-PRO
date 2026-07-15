package com.plugpdv.pdv.utils

import android.content.Context
import android.content.res.Configuration
import java.util.*

object LanguageManager {
    private const val PREFS_NAME = "LanguagePrefs"
    private const val KEY_LANGUAGE = "selected_language"

    @JvmStatic
    fun setLanguage(context: Context, languageCode: String) {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, languageCode).apply()
    }

    @JvmStatic
    fun updateResources(context: Context, languageCode: String): Context {
        val locale = Locale(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    @JvmStatic
    fun getLanguage(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, "pt") ?: "pt"
    }

    @JvmStatic
    fun applyLanguage(context: Context) {
        val language = getLanguage(context)
        setLanguage(context, language)
    }
}
