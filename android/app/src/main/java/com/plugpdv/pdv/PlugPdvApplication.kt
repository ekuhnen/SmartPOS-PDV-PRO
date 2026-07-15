package com.plugpdv.pdv

import android.app.Application
import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.plugpdv.pdv.service.DeviceGuardService
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.DeviceIdProvider
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PlugPdvApplication : Application() {
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    @Inject
    lateinit var deviceGuardService: DeviceGuardService

    override fun onCreate() {
        super.onCreate()
        com.plugpdv.pdv.utils.GlobalCrashHandler.init(this)
        
        // Inicializa o Firebase Analytics
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)

        // Reiniciar monitoramento se estiver logado
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(Constants.TOKEN, null)
        val userId = prefs.getString(Constants.USER_ID, null)
        
        if (!token.isNullOrEmpty() && !userId.isNullOrEmpty()) {
            deviceGuardService.start(this, userId, DeviceIdProvider.get(this))
        }
    }
}
