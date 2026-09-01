package com.plugpdv.pdv.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ComandaMutationDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(mutation: ComandaMutationEntity)

    @Query("SELECT * FROM comanda_mutations WHERE id = :id")
    suspend fun getById(id: String): ComandaMutationEntity?

    @Query("SELECT * FROM comanda_mutations WHERE localComandaId = :localComandaId ORDER BY createdAt ASC")
    suspend fun getByLocalComandaId(localComandaId: String): List<ComandaMutationEntity>

    @Query("SELECT * FROM comanda_mutations WHERE tableId = :tableId AND operationType = 'OPEN_TABLE' AND status NOT IN ('SYNCED', 'RECONCILIATION_REQUIRED') ORDER BY createdAt DESC LIMIT 1")
    suspend fun getPendingOpenForTable(tableId: String): ComandaMutationEntity?

    @Query("SELECT * FROM comanda_mutations WHERE tenantId = :tenantId AND (status = 'PENDING' OR (status = 'PROCESSING' AND claimedAt < :staleThreshold)) AND nextRetryAt <= :now ORDER BY createdAt ASC")
    suspend fun getEligibleMutations(tenantId: String, now: Long, staleThreshold: Long): List<ComandaMutationEntity>

    @Query("UPDATE comanda_mutations SET status = 'PROCESSING', claimToken = :claimToken, claimedAt = :now, updatedAt = :now, attemptCount = attemptCount + 1, lastAttemptAt = :now WHERE id = :id AND (status = 'PENDING' OR (status = 'PROCESSING' AND claimedAt < :staleThreshold))")
    suspend fun claimMutation(id: String, claimToken: String, now: Long, staleThreshold: Long): Int

    @Query("UPDATE comanda_mutations SET status = 'PENDING', claimToken = NULL, claimedAt = NULL, updatedAt = :now WHERE status = 'PROCESSING' AND claimedAt < :staleThreshold")
    suspend fun recoverStaleProcessing(staleThreshold: Long, now: Long): Int

    @Query("UPDATE comanda_mutations SET status = 'SYNCED', claimToken = NULL, claimedAt = NULL, updatedAt = :now, lastErrorCode = NULL, messageKey = NULL WHERE id = :id AND status = 'PROCESSING' AND claimToken = :claimToken")
    suspend fun markSyncedClaimed(id: String, claimToken: String, now: Long): Int

    @Query("UPDATE comanda_mutations SET status = 'PENDING', claimToken = NULL, claimedAt = NULL, nextRetryAt = :nextRetryAt, lastErrorCode = :lastErrorCode, messageKey = :messageKey, updatedAt = :now WHERE id = :id AND status = 'PROCESSING' AND claimToken = :claimToken")
    suspend fun updateRetryClaimed(id: String, claimToken: String, nextRetryAt: Long, lastErrorCode: String?, messageKey: String?, now: Long): Int

    @Query("UPDATE comanda_mutations SET status = 'PAUSED', pauseReason = :pauseReason, claimToken = NULL, claimedAt = NULL, messageKey = :messageKey, updatedAt = :now WHERE id = :id AND status = 'PROCESSING' AND claimToken = :claimToken")
    suspend fun markPausedClaimed(id: String, claimToken: String, pauseReason: String, messageKey: String?, now: Long): Int

    @Query("UPDATE comanda_mutations SET status = 'RECONCILIATION_REQUIRED', reconciliationReason = :reconciliationReason, claimToken = NULL, claimedAt = NULL, messageKey = :messageKey, updatedAt = :now WHERE id = :id AND status = 'PROCESSING' AND claimToken = :claimToken")
    suspend fun markReconciliationRequiredClaimed(id: String, claimToken: String, reconciliationReason: String, messageKey: String?, now: Long): Int

    @Query("UPDATE comanda_mutations SET status = 'PENDING', pauseReason = NULL, updatedAt = :now WHERE tenantId = :tenantId AND actorUserId = :actorUserId AND status = 'PAUSED' AND pauseReason IN ('AUTH_REQUIRED', 'DIFFERENT_ACTOR')")
    suspend fun unpauseEligibleMutations(tenantId: String, actorUserId: String, now: Long): Int

    @Query("SELECT COUNT(*) FROM comanda_mutations WHERE status != 'SYNCED'")
    suspend fun getUnresolvedCount(): Int
}
