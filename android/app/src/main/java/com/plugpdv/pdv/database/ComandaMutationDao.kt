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

    @Query("UPDATE comanda_mutations SET status = 'PENDING', pauseReason = NULL, claimToken = NULL, claimedAt = NULL, nextRetryAt = :now, updatedAt = :now WHERE tenantId = :tenantId AND actorUserId = :actorUserId AND deviceId = :deviceId AND status = 'PAUSED' AND pauseReason IN ('AUTH_REQUIRED', 'DIFFERENT_ACTOR')")
    suspend fun resumeAfterAuthenticatedLogin(
        tenantId: String,
        actorUserId: String,
        deviceId: String,
        now: Long
    ): Int

    @Query("UPDATE comanda_mutations SET status = 'PENDING', pauseReason = NULL, claimToken = NULL, claimedAt = NULL, nextRetryAt = :now, updatedAt = :now WHERE tenantId = :tenantId AND actorUserId = :actorUserId AND deviceId = :deviceId AND status = 'PAUSED' AND pauseReason IN ('DEVICE_BLOCKED', 'DEVICE_NOT_REGISTERED')")
    suspend fun resumeAfterVerifiedDeviceAuthorization(
        tenantId: String,
        actorUserId: String,
        deviceId: String,
        now: Long
    ): Int

    /**
     * Lists all mutations for a tenant that need operator attention.
     * Ordered chronologically to allow FIFO resolution.
     */
    @Query("SELECT * FROM comanda_mutations WHERE tenantId = :tenantId AND status = 'RECONCILIATION_REQUIRED' ORDER BY createdAt ASC")
    suspend fun getReconciliationRequired(tenantId: String): List<ComandaMutationEntity>

    /**
     * R-A: Confirm Remote Success → COMPLETED.
     * CAS-UPDATE: only transitions if status = RECONCILIATION_REQUIRED AND identity triplet matches.
     * Returns number of rows affected (0 = no-op / authority mismatch / wrong state).
     */
    @Query("UPDATE comanda_mutations SET status = 'COMPLETED', resolvedAt = :now, updatedAt = :now WHERE id = :id AND status = 'RECONCILIATION_REQUIRED' AND tenantId = :tenantId AND actorUserId = :actorUserId AND deviceId = :deviceId")
    suspend fun resolveAsCompleted(id: String, actorUserId: String, deviceId: String, tenantId: String, now: Long): Int

    /**
     * R-C: Cancel Local Intent → CANCELLED.
     * CAS-UPDATE: same authority guards as resolveAsCompleted.
     */
    @Query("UPDATE comanda_mutations SET status = 'CANCELLED', resolvedAt = :now, updatedAt = :now WHERE id = :id AND status = 'RECONCILIATION_REQUIRED' AND tenantId = :tenantId AND actorUserId = :actorUserId AND deviceId = :deviceId")
    suspend fun resolveAsCancelled(id: String, actorUserId: String, deviceId: String, tenantId: String, now: Long): Int

    /**
     * R-B: Retry Original → PENDING (same Idempotency-Key K).
     * CAS-UPDATE: resets attempt machinery and re-enables automatic dispatch.
     * Preserves the original mutation id (K) — idempotency guarantee intact.
     */
    @Query("UPDATE comanda_mutations SET status = 'PENDING', reconciliationReason = NULL, lastErrorCode = NULL, messageKey = NULL, claimToken = NULL, claimedAt = NULL, nextRetryAt = :now, resolvedAt = NULL, updatedAt = :now WHERE id = :id AND status = 'RECONCILIATION_REQUIRED' AND tenantId = :tenantId AND actorUserId = :actorUserId AND deviceId = :deviceId")
    suspend fun resolveAsRetry(id: String, actorUserId: String, deviceId: String, tenantId: String, now: Long): Int

    /**
     * Helper for R-D (Replace): mark original mutation as CANCELLED before inserting the new one.
     * Called inside the same transaction as the new mutation insert.
     */
    @Query("UPDATE comanda_mutations SET status = 'CANCELLED', resolvedAt = :now, updatedAt = :now WHERE id = :id AND status = 'RECONCILIATION_REQUIRED' AND tenantId = :tenantId AND actorUserId = :actorUserId AND deviceId = :deviceId")
    suspend fun markCancelledForReplacement(id: String, actorUserId: String, deviceId: String, tenantId: String, now: Long): Int

    /**
     * Mutations that still need attention: excludes finalized states.
     * SYNCED, COMPLETED, and CANCELLED are all terminal — they require no further action.
     */
    @Query("SELECT COUNT(*) FROM comanda_mutations WHERE status NOT IN ('SYNCED', 'COMPLETED', 'CANCELLED')")
    suspend fun getUnresolvedCount(): Int
}
