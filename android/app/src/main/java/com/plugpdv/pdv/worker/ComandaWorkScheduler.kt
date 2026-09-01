package com.plugpdv.pdv.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComandaWorkScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val UNIQUE_WORK_NAME = "plugpdv_comanda_mutations_sync"
        private const val TAG = "ComandaWorkScheduler"
    }

    fun scheduleCommandSync() {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<ComandaSyncWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    androidx.work.WorkRequest.MIN_BACKOFF_MILLIS,
                    TimeUnit.MILLISECONDS
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "Comanda mutations sync scheduled (KEEP)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule comanda sync: ${e.message}", e)
        }
    }

    fun scheduleRetry(delayMs: Long) {
        try {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<ComandaSyncWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMs.coerceAtLeast(1000L), TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME + "_retry",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
            Log.d(TAG, "Comanda mutations retry scheduled in ${delayMs}ms")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to schedule comanda retry: ${e.message}", e)
        }
    }
}
