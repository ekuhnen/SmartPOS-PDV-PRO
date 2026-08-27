package com.plugpdv.pdv.outbox

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaleSyncScheduler @Inject constructor() {

    companion object {
        private const val TAG = "SaleSyncScheduler"
    }

    fun scheduleSync(context: Context) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<SaleSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                SaleSyncWorker.UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "Sincronização da Outbox agendada via WorkManager (UniqueWork: ${SaleSyncWorker.UNIQUE_WORK_NAME})")
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao agendar WorkManager: ${e.message}", e)
        }
    }
}
