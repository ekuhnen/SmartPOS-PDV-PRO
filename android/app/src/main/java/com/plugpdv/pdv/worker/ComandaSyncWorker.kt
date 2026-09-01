package com.plugpdv.pdv.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.plugpdv.pdv.dispatcher.ComandaOutboxDispatcher
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ComandaSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val dispatcher: ComandaOutboxDispatcher
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "ComandaSyncWorker"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Iniciando worker de sincronização de mutações de comanda...")
        try {
            var loopCount = 0
            while (loopCount < 5) {
                loopCount++
                val result = dispatcher.dispatchEligibleBatch()
                Log.d(TAG, "Passada $loopCount: processadas ${result.processedCount}, restantes: ${result.remainingCount}, motivo: ${result.stopReason}")

                if (result.processedCount == 0 || result.stopReason != "PROGRESSED") {
                    break
                }
            }
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado no ComandaSyncWorker: ${e.message}", e)
            return Result.retry()
        }
    }
}
