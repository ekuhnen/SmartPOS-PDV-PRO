package com.plugpdv.pdv.outbox

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.plugpdv.pdv.database.LocalSaleDao
import com.plugpdv.pdv.repository.SaleOutboxRepository
import com.plugpdv.pdv.repository.StopReason
import com.plugpdv.pdv.utils.OutboxSyncManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SaleSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val saleOutboxRepository: SaleOutboxRepository,
    private val localSaleDao: LocalSaleDao,
    private val outboxSyncManager: OutboxSyncManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "SaleSyncWorker"
        const val UNIQUE_WORK_NAME = "plugpdv-sales-outbox-sync"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Iniciando worker de sincronização da Outbox de vendas e comandas...")

        try {
            var loopCount = 0
            var lastStopReason = StopReason.EMPTY

            while (loopCount < 5) {
                loopCount++
                val result = saleOutboxRepository.processOutboxBatch()
                lastStopReason = result.stopReason
                Log.d(TAG, "Passada $loopCount: processadas ${result.processedCount} vendas, restantes: ${result.remainingCount}, motivo: ${result.stopReason}")

                if (result.processedCount == 0 || result.stopReason != StopReason.PROGRESSED) {
                    break
                }
            }

            // Drenagem estrita e durável das operações da outbox geral (incluindo COMANDA_CHECKOUT_COMMIT)
            outboxSyncManager.drainPendingOutbox()

            return when (lastStopReason) {
                StopReason.EMPTY,
                StopReason.PROGRESSED,
                StopReason.AUTH_REQUIRED,
                StopReason.PERMANENT_ONLY -> Result.success()
                StopReason.BACKOFF_REQUIRED,
                StopReason.TRANSIENT_FAILURE,
                StopReason.NO_PROGRESS -> Result.retry()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado no SaleSyncWorker: ${e.message}", e)
            return Result.retry()
        }
    }
}
