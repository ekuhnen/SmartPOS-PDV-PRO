package com.plugpdv.pdv.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "payment_attempts",
    indices = [
        Index(value = ["idempotencyKey"], unique = true),
        Index(value = ["tableNumber"]),
        Index(value = ["status"]),
        Index(value = ["startedAt"])
    ]
)
data class PaymentAttemptEntity(
    @PrimaryKey
    val reference: String,                  // Ex: requestId / payment reference gerado no toque
    val idempotencyKey: String,             // Chave de idempotência única
    val nonce: String,                      // Nonce de segurança gerado para eco
    val amount: Long,                       // Em unidade mínima da moeda (ex: centavos BRL = 1500; PYG = 150000)
    val currency: String,                   // Código da moeda (BRL, PYG, ARS, USD, etc.)
    val status: String = "PENDING",         // "PENDING", "APPROVED", "REJECTED", "UNKNOWN", "CANCELLED"
    val startedAt: Long,                    // Timestamp de início (quando operador tocou em pagar)
    val completedAt: Long? = null,
    val paymentMethod: String? = null,
    val tableNumber: Int? = null,
    val orderId: String? = null,
    val description: String? = null,
    val rawCallbackUri: String? = null,
    val paymentAppPaymentId: String? = null,
    val statusMessage: String? = null
) {
    companion object {
        const val STATUS_PREPARED = "PREPARED"
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_UNKNOWN = "UNKNOWN"
        const val STATUS_CANCELLED = "CANCELLED"
    }
}
