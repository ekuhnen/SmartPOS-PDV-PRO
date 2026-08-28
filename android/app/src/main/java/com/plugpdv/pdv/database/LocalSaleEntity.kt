package com.plugpdv.pdv.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_sales")
data class LocalSaleEntity(
    @PrimaryKey
    val localId: String,           // UUID gerado localmente
    val apiId: String? = null,     // ID retornado pela API após sync
    val timestamp: Long = 0L,      // Preservado para retrocompatibilidade
    val createdAt: Long = timestamp, // System.currentTimeMillis() no momento do toque
    val updatedAt: Long = timestamp, // System.currentTimeMillis() do último update de estado
    val total: Double,
    val currency: String,
    val paymentMethod: String,
    val operatorId: String? = null,
    val operatorName: String? = null,
    val sessionId: String? = null,
    val itemsJson: String,         // Lista de SaleItem serializada como JSON
    val customerName: String? = "Consumidor Final",
    val taxAmount: Double = 0.0,
    val serviceFeeAmount: Double = 0.0,
    val serviceFeeKind: String? = null,
    val convertedTotal: Double = 0.0,
    val payloadJson: String = "{}", // Snapshot imutável do SaleRequest (sem tokens/senhas)
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val lastAttemptAt: Long? = null,
    val syncStatus: String = STATUS_PENDING,
    val syncedToApi: Boolean = false,
    val idempotencyKeyUsed: Boolean = false // Marcador explícito de transmissão com Idempotency-Key
) {
    companion object {
        const val STATUS_WAITING_PAYMENT = "WAITING_PAYMENT"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_SYNCING = "SYNCING"
        const val STATUS_SYNCED = "SYNCED"
        const val STATUS_FAILED_RETRYABLE = "FAILED_RETRYABLE"
        const val STATUS_FAILED_PERMANENT = "FAILED_PERMANENT"
        const val STATUS_UNKNOWN = "UNKNOWN"
        const val STATUS_NEEDS_RECONCILIATION = "NEEDS_RECONCILIATION"
    }
}
