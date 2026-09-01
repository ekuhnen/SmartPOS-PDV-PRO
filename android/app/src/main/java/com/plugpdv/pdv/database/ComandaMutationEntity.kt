package com.plugpdv.pdv.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "comanda_mutations",
    indices = [
        Index(value = ["tenantId", "status", "nextRetryAt"]),
        Index(value = ["localComandaId", "createdAt"]),
        Index(value = ["localItemId"])
    ]
)
data class ComandaMutationEntity(
    @PrimaryKey
    val id: String,                         // UUID da operação gerado no toque / Idempotency-Key K
    val operationType: String,              // "OPEN_TABLE", "ADD_ITEM", "SEND_KITCHEN"
    val tenantId: String,                   // Canonical tenant owner ID
    val actorUserId: String,                // ID do usuário operador no momento do toque
    val deviceId: String,                   // ID estável do hardware/terminal
    val localComandaId: String,             // Identidade local estável L1
    val tableId: String,                    // ID da mesa de destino
    val localItemId: String? = null,        // ID do item local (relevante para ADD_ITEM)
    val payloadJson: String,                // Payload semântico congelado no momento do toque
    val resolvedPayloadJson: String? = null,// Payload exato de rede persistido antes do dispatch
    val createdAt: Long,                    // Timestamp do toque
    val updatedAt: Long,                    // Timestamp da última transição
    val attemptCount: Int = 0,              // Contagem de tentativas
    val lastAttemptAt: Long? = null,        // Timestamp da última tentativa
    val nextRetryAt: Long,                  // Próximo agendamento com backoff
    val status: String,                     // "PENDING", "WAITING_DEPENDENCY", "PROCESSING", "PAUSED", "SYNCED", "RECONCILIATION_REQUIRED"
    val pauseReason: String? = null,        // "AUTH_REQUIRED", "DIFFERENT_ACTOR", "DEVICE_BLOCKED", "UPDATE_REQUIRED", etc.
    val reconciliationReason: String? = null,// "CATALOG_SNAPSHOT_CONFLICT", "TABLE_DOMAIN_CONFLICT", etc.
    val claimToken: String? = null,         // Token do lease do worker
    val claimedAt: Long? = null,            // Timestamp do claim do lease
    val lastErrorCode: String? = null,      // Código de erro HTTP ou de domínio
    val messageKey: String? = null          // Chave de tradução
)
