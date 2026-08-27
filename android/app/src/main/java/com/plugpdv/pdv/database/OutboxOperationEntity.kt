package com.plugpdv.pdv.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "outbox_operations",
    indices = [
        Index(value = ["targetGroupKey"]),
        Index(value = ["status"]),
        Index(value = ["createdAt"])
    ]
)
data class OutboxOperationEntity(
    @PrimaryKey
    val id: String,                         // UUID da operação gerado no momento do toque
    val operationType: String,              // "SALE_DIRECT", "COMMAND_ADD_ITEM", "COMMAND_CANCEL_ITEM", "COMMAND_OPEN", etc.
    val targetGroupKey: String,             // ID da comanda/mesa (ex: "comanda_123" ou "direct_sale")
    val payloadJson: String,                // Dados serializados da requisição
    val createdAt: Long,                    // Timestamp do toque do operador
    val idempotencyKey: String = id,        // Chave de idempotência gerada no toque
    val serverSeq: Long? = null,            // Sequência global atribuída pelo servidor
    val attemptCount: Int = 0,              // Número de tentativas de envio
    val lastAttemptAt: Long? = null,        // Timestamp da última tentativa
    val nextRetryAt: Long = createdAt,      // Próximo agendamento com backoff
    val status: String = "PENDING",         // "PENDING", "PROCESSING", "FAILED", "SYNCED"
    val lastError: String? = null,
    val messageKey: String? = null,         // Chave de mensagem de erro traduzível
    val isRetriable: Boolean = true
)
