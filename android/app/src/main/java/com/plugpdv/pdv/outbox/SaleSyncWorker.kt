package com.plugpdv.pdv.outbox

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.plugpdv.pdv.database.LocalSaleDao
import com.plugpdv.pdv.repository.SaleOutboxRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class SaleSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted workerParams: WorkerParameters,
    private val saleOutboxRepository: SaleOutboxRepository,
    private val localSaleDao: LocalSaleDao,
    private val outboxSyncManager: com.plugpdv.pdv.utils.OutboxSyncManager
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val TAG = "SaleSyncWorker"
        const val UNIQUE_WORK_NAME = "plugpdv-sales-outbox-sync"
    }

    override suspend fun doWork(): Result {
        Log.d(TAG, "Iniciando worker de sincronização da Outbox de vendas e comandas...")

        try {
            var loopCount = 0
            while (true) {
                val pending = localSaleDao.getPendingSales()
                if (pending.isEmpty()) {
                    break
                }

                loopCount++
                Log.d(TAG, "Passada $loopCount: processando ${pending.size} vendas pendentes...")
                saleOutboxRepository.processOutboxBatch()
            }

            // Drenagem estrita e durável das operações da outbox geral (incluindo COMANDA_CHECKOUT_COMMIT)
            outboxSyncManager.drainPendingOutbox()

            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado no SaleSyncWorker: ${e.message}", e)
            return Result.retry()
        }
    }
}
