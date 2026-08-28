package com.plugpdv.pdv.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PaymentAttemptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(attempt: PaymentAttemptEntity)

    @Update
    suspend fun update(attempt: PaymentAttemptEntity)

    @Query("SELECT * FROM payment_attempts WHERE reference = :reference LIMIT 1")
    suspend fun getByReference(reference: String): PaymentAttemptEntity?

    @Query("SELECT * FROM payment_attempts WHERE idempotencyKey = :idempotencyKey LIMIT 1")
    suspend fun getByIdempotencyKey(idempotencyKey: String): PaymentAttemptEntity?

    @Query("SELECT * FROM payment_attempts WHERE status = 'PENDING' OR status = 'UNKNOWN' ORDER BY startedAt DESC")
    suspend fun getPendingOrUndeterminedAttempts(): List<PaymentAttemptEntity>

    @Query("SELECT * FROM payment_attempts WHERE tableNumber = :tableNumber AND (status = 'PENDING' OR status = 'UNKNOWN') ORDER BY startedAt DESC LIMIT 1")
    suspend fun getLatestPendingForTable(tableNumber: Int): PaymentAttemptEntity?

    @Query("SELECT * FROM payment_attempts WHERE status = 'APPROVED'")
    suspend fun getApprovedAttempts(): List<PaymentAttemptEntity>

    @Query("SELECT * FROM payment_attempts WHERE status = 'APPROVED' AND ((:tableNumber IS NOT NULL AND tableNumber = :tableNumber) OR (:orderId IS NOT NULL AND orderId = :orderId)) ORDER BY startedAt DESC")
    suspend fun getApprovedAttemptsForTableOrOrder(tableNumber: Int?, orderId: String?): List<PaymentAttemptEntity>

    @Query("SELECT * FROM payment_attempts ORDER BY startedAt DESC LIMIT 50")
    fun getRecentAttemptsFlow(): Flow<List<PaymentAttemptEntity>>

    @Query("DELETE FROM payment_attempts WHERE reference = :reference")
    suspend fun deleteByReference(reference: String)

    @Query("SELECT COUNT(*) FROM payment_attempts WHERE status IN ('PREPARED', 'PENDING', 'UNKNOWN', 'NEEDS_RECONCILIATION')")
    suspend fun getUnresolvedCount(): Int
}
