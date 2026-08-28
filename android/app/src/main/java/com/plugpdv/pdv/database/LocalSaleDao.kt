package com.plugpdv.pdv.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface LocalSaleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sale: LocalSaleEntity)

    @Update
    suspend fun update(sale: LocalSaleEntity)

    @Query("UPDATE local_sales SET syncedToApi = 1, syncStatus = 'SYNCED', apiId = :apiId, updatedAt = :updatedAt WHERE localId = :localId")
    suspend fun markAsSynced(localId: String, apiId: String, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE local_sales SET syncStatus = :status, lastError = :error, attemptCount = attemptCount + 1, lastAttemptAt = :now, updatedAt = :now WHERE localId = :localId")
    suspend fun markAsStatus(localId: String, status: String, error: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE local_sales SET syncStatus = 'SYNCING', updatedAt = :now WHERE localId = :localId AND syncStatus IN ('PENDING', 'FAILED_RETRYABLE')")
    suspend fun markAsSyncing(localId: String, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE local_sales SET idempotencyKeyUsed = 1, updatedAt = :now WHERE localId = :localId")
    suspend fun markAsKeyed(localId: String, now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE local_sales SET syncStatus = 'UNKNOWN', lastError = 'Processo interrompido durante SYNCING (Crash Recovery Legado)', updatedAt = :now WHERE syncStatus = 'SYNCING' AND idempotencyKeyUsed = 0")
    suspend fun recoverStaleSyncingUnkeyedToUnknown(now: Long = System.currentTimeMillis()): Int

    @Query("UPDATE local_sales SET syncStatus = 'PENDING', lastError = 'Recuperado de SYNCING interrompido (Idempotent Retry)', updatedAt = :now WHERE syncStatus = 'SYNCING' AND idempotencyKeyUsed = 1")
    suspend fun recoverStaleSyncingKeyedToPending(now: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM local_sales WHERE syncStatus IN ('PENDING', 'FAILED_RETRYABLE') ORDER BY createdAt ASC")
    suspend fun getPendingSales(): List<LocalSaleEntity>

    @Query("SELECT * FROM local_sales WHERE syncedToApi = 0 OR syncStatus != 'SYNCED' ORDER BY createdAt ASC")
    suspend fun getPendingSync(): List<LocalSaleEntity>

    @Query("SELECT * FROM local_sales WHERE localId = :localId LIMIT 1")
    suspend fun getById(localId: String): LocalSaleEntity?

    @Query("SELECT * FROM local_sales WHERE syncStatus IN ('WAITING_PAYMENT', 'NEEDS_RECONCILIATION') ORDER BY createdAt ASC")
    suspend fun getWaitingPaymentSales(): List<LocalSaleEntity>

    @Query("SELECT * FROM local_sales ORDER BY createdAt DESC LIMIT 50")
    suspend fun getRecentSales(): List<LocalSaleEntity>
}
