package com.plugpdv.pdv.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallState
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability

class InAppUpdateManager(
    private val activity: Activity,
    private val appUpdateManager: AppUpdateManager = AppUpdateManagerFactory.create(activity)
) {

    companion object {
        private const val TAG = "InAppUpdateManager"
        const val STALENESS_DAYS_IMMEDIATE_THRESHOLD = 5
    }

    private var isUpdateStarted = false

    private val installStateUpdatedListener = InstallStateUpdatedListener { state: InstallState ->
        when (state.installStatus()) {
            InstallStatus.DOWNLOADED -> {
                Log.i(TAG, "Atualização baixada com sucesso (FLEXIBLE). Concluindo instalação...")
                onFlexibleUpdateDownloaded()
            }
            InstallStatus.DOWNLOADING -> {
                val bytesDownloaded = state.bytesDownloaded()
                val totalBytes = state.totalBytesToDownload()
                Log.d(TAG, "Baixando atualização: $bytesDownloaded / $totalBytes bytes")
            }
            InstallStatus.FAILED -> {
                Log.e(TAG, "Falha na instalação da atualização: erro código ${state.installErrorCode()}")
                isUpdateStarted = false
            }
            InstallStatus.CANCELED -> {
                Log.w(TAG, "Instalação da atualização cancelada pelo usuário")
                isUpdateStarted = false
            }
            else -> {
                Log.d(TAG, "InstallStatus: ${state.installStatus()}")
            }
        }
    }

    init {
        try {
            appUpdateManager.registerListener(installStateUpdatedListener)
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao registrar listener de InAppUpdate: ${e.message}")
        }
    }

    /**
     * Verifica disponibilidade de atualização na Google Play Store.
     * @param forceImmediate se true, força atualização imediata (ex: recebido 426 upgrade_required)
     * @param onUpdateDownloaded callback opcional quando o download flexible for concluído
     */
    fun checkForUpdates(
        forceImmediate: Boolean = false,
        onUpdateDownloaded: (() -> Unit)? = null
    ) {
        if (isUpdateStarted) {
            Log.d(TAG, "Atualização já em andamento, ignorando nova verificação.")
            return
        }

        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            val availability = appUpdateInfo.updateAvailability()
            val stalenessDays = appUpdateInfo.clientVersionStalenessDays() ?: 0

            Log.d(TAG, "UpdateAvailability: $availability, stalenessDays: $stalenessDays, forceImmediate: $forceImmediate")

            if (availability == UpdateAvailability.UPDATE_AVAILABLE) {
                val preferredType = if (forceImmediate || stalenessDays >= STALENESS_DAYS_IMMEDIATE_THRESHOLD) {
                    AppUpdateType.IMMEDIATE
                } else {
                    AppUpdateType.FLEXIBLE
                }

                startUpdateWithFallback(appUpdateInfo, preferredType)
            } else if (availability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                // Atualização imediata já estava rodando e o usuário voltou para a tela
                resumeImmediateUpdate(appUpdateInfo)
            }
        }.addOnFailureListener { e ->
            Log.w(TAG, "Não foi possível verificar In-App Updates no momento: ${e.message}")
        }
    }

    private fun startUpdateWithFallback(appUpdateInfo: AppUpdateInfo, preferredType: Int) {
        if (isUpdateStarted) return

        val canRunPreferred = appUpdateInfo.isUpdateTypeAllowed(preferredType)
        val targetType = if (canRunPreferred) {
            preferredType
        } else {
            // Plano B: se o preferido não for permitido, tenta o outro tipo
            val fallbackType = if (preferredType == AppUpdateType.IMMEDIATE) AppUpdateType.FLEXIBLE else AppUpdateType.IMMEDIATE
            if (appUpdateInfo.isUpdateTypeAllowed(fallbackType)) {
                Log.w(TAG, "Tipo de atualização $preferredType não permitido. Usando fallback $fallbackType")
                fallbackType
            } else {
                Log.e(TAG, "Nenhum tipo de In-App Update é suportado pela plataforma neste momento.")
                return
            }
        }

        try {
            isUpdateStarted = true
            val options = AppUpdateOptions.newBuilder(targetType).build()
            appUpdateManager.startUpdateFlow(appUpdateInfo, activity, options)
        } catch (e: Exception) {
            isUpdateStarted = false
            Log.e(TAG, "Falha ao iniciar startUpdateFlow: ${e.message}", e)
        }
    }

    private fun resumeImmediateUpdate(appUpdateInfo: AppUpdateInfo) {
        try {
            val options = AppUpdateOptions.newBuilder(AppUpdateType.IMMEDIATE).build()
            appUpdateManager.startUpdateFlow(appUpdateInfo, activity, options)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao retomar atualização imediata: ${e.message}")
        }
    }

    /**
     * Deve ser chamado no onResume da Activity para tratar atualizações já em andamento.
     */
    fun onResume() {
        appUpdateManager.appUpdateInfo.addOnSuccessListener { appUpdateInfo ->
            if (appUpdateInfo.installStatus() == InstallStatus.DOWNLOADED) {
                onFlexibleUpdateDownloaded()
            } else if (appUpdateInfo.updateAvailability() == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
                resumeImmediateUpdate(appUpdateInfo)
            }
        }.addOnFailureListener { e ->
            Log.w(TAG, "Erro no onResume ao checar In-App Updates: ${e.message}")
        }
    }

    private fun onFlexibleUpdateDownloaded() {
        try {
            appUpdateManager.completeUpdate()
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao chamar completeUpdate(): ${e.message}")
        }
    }

    fun unregister() {
        try {
            appUpdateManager.unregisterListener(installStateUpdatedListener)
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao desregistrar listener: ${e.message}")
        }
    }
}
