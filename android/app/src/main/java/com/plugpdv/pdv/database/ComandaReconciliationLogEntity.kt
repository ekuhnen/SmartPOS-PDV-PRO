package com.plugpdv.pdv.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Immutable audit record written once per reconciliation resolution action.
 *
 * INVARIANTS:
 * - Insert with ABORT — the same `id` must never be written twice.
 * - One record per resolution action. Multiple records per `mutationId` are possible
 *   if the mutation goes through multiple resolution attempts (e.g., retry → reconciliation
 *   again → cancel).
 * - Never updated in-place. Add a new record for each action.
 */
@Entity(
    tableName = "comanda_reconciliation_log",
    indices = [
        Index(value = ["mutationId"]),
        Index(value = ["tenantId", "resolvedAt"])
    ]
)
data class ComandaReconciliationLogEntity(
    @PrimaryKey
    val id: String,                         // UUID único por entrada de log
    val mutationId: String,                 // FK (sem FK constraint) → comanda_mutations.id
    val tenantId: String,                   // Denormalizado para isolamento de tenant
    val actorUserId: String,               // Ator que executou a resolução
    val deviceId: String,                  // Device que executou a resolução
    val previousState: String,             // Estado antes da resolução (tipicamente "RECONCILIATION_REQUIRED")
    val resolutionAction: String,          // "COMPLETED", "CANCELLED", "RETRY", "REPLACED"
    val resultState: String,               // Estado resultante da mutation original
    val reconciliationReason: String?,     // reconciliationReason copiado da mutation
    val lastHttpStatus: Int?,              // Código HTTP da última tentativa, se disponível
    val notes: String?,                    // Texto livre opcional do operador/sistema
    val resolvedAt: Long                   // Timestamp epoch ms da resolução
)
