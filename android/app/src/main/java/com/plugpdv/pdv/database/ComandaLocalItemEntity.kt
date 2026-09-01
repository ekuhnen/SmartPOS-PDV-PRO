package com.plugpdv.pdv.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "comanda_local_items",
    indices = [
        Index(value = ["localComandaId", "localStatus"]),
        Index(value = ["tenantId", "serverItemId"], unique = true)
    ]
)
data class ComandaLocalItemEntity(
    @PrimaryKey
    val localItemId: String,                // UUID local do item no cliente
    val localComandaId: String,             // Identidade local estável L1
    val tenantId: String,                   // Canonical tenant owner ID
    val serverItemId: String? = null,       // UUID canônico do item no backend (null até sincronizar)
    val productId: String,                  // ID do produto no catálogo
    val productNameSnapshot: String,        // Nome do produto no momento do toque
    val quantity: Int,                      // Quantidade do item
    val observation: String? = null,        // Observação do item
    val commercialRevision: String,         // commercial_revision backend-authored congelada no toque
    val displayAmountScaled: Long,          // Valor de exibição escalado (ex: 2500 para R$ 25,00)
    val displayCurrency: String,            // Moeda de exibição ativa no toque (ex: "BRL", "USD", "PYG")
    val displayDecimals: Int,               // Casas decimais de exibição da moeda (ex: 2 ou 0)
    val localStatus: String,                // "DRAFT", "SEND_PENDING", "KITCHEN_CONFIRMED", "RECONCILIATION_REQUIRED"
    val serverStatus: String? = null,       // Status observado do backend ("RASCUNHO", "ATIVO", etc.)
    val createdAt: Long,                    // Timestamp de criação
    val updatedAt: Long,                    // Timestamp de atualização
    val reconciliationReason: String? = null// Motivo de conciliação caso haja conflito
)
