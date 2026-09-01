package com.plugpdv.pdv

import android.app.Application
import android.content.Context
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.google.firebase.analytics.FirebaseAnalytics
import com.plugpdv.pdv.outbox.SaleSyncScheduler
import com.plugpdv.pdv.service.DeviceGuardService
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.DeviceIdProvider
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class PlugPdvApplication : Application(), Configuration.Provider {
    private lateinit var firebaseAnalytics: FirebaseAnalytics

    @Inject
    lateinit var deviceGuardService: DeviceGuardService

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var saleSyncScheduler: SaleSyncScheduler

    @Inject
    lateinit var comandaWorkScheduler: com.plugpdv.pdv.worker.ComandaWorkScheduler

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        com.plugpdv.pdv.utils.GlobalCrashHandler.init(this)
        com.plugpdv.pdv.utils.CurrencyManager.getInstance().init(this)
        
        // Inicializa o Firebase Analytics
        firebaseAnalytics = FirebaseAnalytics.getInstance(this)

        // Agendar sincronização da outbox de vendas pendentes ao iniciar o aplicativo
        saleSyncScheduler.scheduleSync(this)

        // Agendar sincronização e recuperação de mutações duráveis de comanda
        comandaWorkScheduler.scheduleCommandSync()

        // Reiniciar monitoramento se estiver logado
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(Constants.TOKEN, null)
        val userId = prefs.getString(Constants.USER_ID, null)
        
        if (!token.isNullOrEmpty() && !userId.isNullOrEmpty()) {
            deviceGuardService.start(this, userId, DeviceIdProvider.get(this))
        }
    }
}
