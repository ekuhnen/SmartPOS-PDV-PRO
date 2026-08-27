package com.plugpdv.pdv.repository

import com.plugpdv.pdv.database.LocalSaleDao
import com.plugpdv.pdv.database.LocalSaleEntity
import org.junit.Ignore

@Ignore("Helper class for unit tests")
open class FakeLocalSaleDao : LocalSaleDao {
    val sales = mutableListOf<LocalSaleEntity>()

    override suspend fun insert(sale: LocalSaleEntity) {
        sales.removeAll { it.localId == sale.localId }
        sales.add(sale)
    }

    override suspend fun update(sale: LocalSaleEntity) {
        insert(sale)
    }

    override suspend fun markAsSynced(localId: String, apiId: String, updatedAt: Long) {
        val index = sales.indexOfFirst { it.localId == localId }
        if (index >= 0) {
            sales[index] = sales[index].copy(
                syncedToApi = true,
                syncStatus = LocalSaleEntity.STATUS_SYNCED,
                apiId = apiId,
                updatedAt = updatedAt
            )
        }
    }

    override suspend fun markAsStatus(localId: String, status: String, error: String?, now: Long) {
        val index = sales.indexOfFirst { it.localId == localId }
        if (index >= 0) {
            sales[index] = sales[index].copy(
                syncStatus = status,
                lastError = error,
                attemptCount = sales[index].attemptCount + 1,
                lastAttemptAt = now,
                updatedAt = now
            )
        }
    }

    override suspend fun markAsSyncing(localId: String, now: Long): Int {
        val index = sales.indexOfFirst { it.localId == localId }
        if (index >= 0 && (sales[index].syncStatus == LocalSaleEntity.STATUS_PENDING || sales[index].syncStatus == LocalSaleEntity.STATUS_FAILED_RETRYABLE)) {
            sales[index] = sales[index].copy(syncStatus = LocalSaleEntity.STATUS_SYNCING, updatedAt = now)
            return 1
        }
        return 0
    }

    override suspend fun markAsKeyed(localId: String, now: Long): Int {
        val index = sales.indexOfFirst { it.localId == localId }
        if (index >= 0) {
            sales[index] = sales[index].copy(idempotencyKeyUsed = true, updatedAt = now)
            return 1
        }
        return 0
    }

    override suspend fun recoverStaleSyncingUnkeyedToUnknown(now: Long): Int {
        var count = 0
        for (i in sales.indices) {
            if (sales[i].syncStatus == LocalSaleEntity.STATUS_SYNCING && !sales[i].idempotencyKeyUsed) {
                sales[i] = sales[i].copy(
                    syncStatus = LocalSaleEntity.STATUS_UNKNOWN,
                    lastError = "Processo interrompido durante SYNCING (Crash Recovery Legado)",
                    updatedAt = now
                )
                count++
            }
        }
        return count
    }

    override suspend fun recoverStaleSyncingKeyedToPending(now: Long): Int {
        var count = 0
        for (i in sales.indices) {
            if (sales[i].syncStatus == LocalSaleEntity.STATUS_SYNCING && sales[i].idempotencyKeyUsed) {
                sales[i] = sales[i].copy(
                    syncStatus = LocalSaleEntity.STATUS_PENDING,
                    lastError = "Recuperado de SYNCING interrompido (Idempotent Retry)",
                    updatedAt = now
                )
                count++
            }
        }
        return count
    }

    override suspend fun getPendingSales(): List<LocalSaleEntity> {
        return sales.filter { it.syncStatus == LocalSaleEntity.STATUS_PENDING || it.syncStatus == LocalSaleEntity.STATUS_FAILED_RETRYABLE }
    }

    override suspend fun getPendingSync(): List<LocalSaleEntity> {
        return sales.filter { !it.syncedToApi || it.syncStatus != LocalSaleEntity.STATUS_SYNCED }
    }

    override suspend fun getRecentSales(): List<LocalSaleEntity> {
        return sales.sortedByDescending { it.createdAt }.take(50)
    }
}
