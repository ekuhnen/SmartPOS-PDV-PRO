package com.plugpdv.pdv.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface OutboxDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(operation: OutboxOperationEntity)

    @Update
    suspend fun update(operation: OutboxOperationEntity)

    @Query("SELECT * FROM outbox_operations WHERE status = 'PENDING' AND nextRetryAt <= :currentTime ORDER BY createdAt ASC")
    suspend fun getPendingOperations(currentTime: Long): List<OutboxOperationEntity>

    @Query("SELECT * FROM outbox_operations WHERE targetGroupKey = :groupKey AND status IN ('PENDING', 'PROCESSING') ORDER BY createdAt ASC")
    suspend fun getPendingForGroup(groupKey: String): List<OutboxOperationEntity>

    @Query("SELECT DISTINCT targetGroupKey FROM outbox_operations WHERE status = 'PENDING' AND nextRetryAt <= :currentTime")
    suspend fun getDistinctPendingGroups(currentTime: Long): List<String>

    @Query("SELECT * FROM outbox_operations WHERE status = 'PENDING' AND nextRetryAt <= :currentTime ORDER BY createdAt ASC LIMIT :limit")
    suspend fun getPendingBatch(currentTime: Long, limit: Int = 50): List<OutboxOperationEntity>

    @Query("UPDATE outbox_operations SET status = 'SYNCED', serverSeq = :serverSeq WHERE id = :id")
    suspend fun markAsSyncedWithSeq(id: String, serverSeq: Long?)

    @Query("UPDATE outbox_operations SET status = 'SYNCED' WHERE id = :id")
    suspend fun markAsSynced(id: String)

    @Query("UPDATE outbox_operations SET status = 'FAILED', lastError = :error, messageKey = :messageKey, isRetriable = :isRetriable WHERE id = :id")
    suspend fun markAsFailedWithKey(id: String, error: String, messageKey: String?, isRetriable: Boolean)

    @Query("UPDATE outbox_operations SET status = 'FAILED', lastError = :error WHERE id = :id")
    suspend fun markAsFailed(id: String, error: String)

    @Query("SELECT COUNT(*) FROM outbox_operations WHERE status IN ('PENDING', 'PROCESSING')")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT MIN(createdAt) FROM outbox_operations WHERE status IN ('PENDING', 'PROCESSING')")
    fun getOldestPendingTimestampFlow(): Flow<Long?>

    @Query("SELECT * FROM outbox_operations WHERE id = :id")
    suspend fun getById(id: String): OutboxOperationEntity?

    @Query("SELECT * FROM outbox_operations WHERE status = 'WAITING_PAYMENT'")
    suspend fun getWaitingPaymentOperations(): List<OutboxOperationEntity>

    @Query("SELECT * FROM outbox_operations WHERE targetGroupKey = :groupKey AND status IN ('WAITING_PAYMENT', 'PENDING', 'PROCESSING')")
    suspend fun getActiveOperationsForGroup(groupKey: String): List<OutboxOperationEntity>

    @Query("SELECT COUNT(*) FROM outbox_operations WHERE status IN ('PENDING', 'PROCESSING')")
    suspend fun getPendingCount(): Int

    @Query("SELECT MIN(createdAt) FROM outbox_operations WHERE status IN ('PENDING', 'PROCESSING')")
    suspend fun getOldestPendingTimestamp(): Long?
}
