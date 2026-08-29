package com.plugpdv.pdv.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "comanda_snapshots",
    indices = [
        Index(
            value = ["tenantId", "serverComandaId"],
            unique = true
        ),
        Index(value = ["tableId"]),
        Index(value = ["localStatus"]),
        Index(value = ["syncStatus"]),
        Index(value = ["requiresReconciliation"])
    ]
)
data class ComandaSnapshotEntity(

    @PrimaryKey
    val localComandaId: String,

    val serverComandaId: String?,

    val tenantId: String,

    val tableId: String?,
    val tableNumber: Int?,
    val customerIdentifier: String?,

    // Frozen financial authority
    val baseCurrency: String?,
    val baseMinorUnitDigits: Int?,

    // Business state and synchronization state are SEPARATE
    val serverStatus: String?,
    val localStatus: String,
    val syncStatus: String,

    // Reserved for future concurrency contract.
    // DO NOT invent values not supplied by backend.
    val serverRevision: Long?,
    val localRevision: Long,

    // Values in frozen baseCurrency minor units.
    // Nullable when authoritative money is incomplete.
    val totalBaseMinor: Long?,
    val paidBaseMinor: Long?,
    val balanceBaseMinor: Long?,

    val itemsJson: String,
    val paymentsJson: String,

    val requiresReconciliation: Boolean,
    val reconciliationReason: String?,

    // Backend currently does not provide an authoritative
    // revision/updated timestamp contract here.
    val serverUpdatedAt: Long?,

    // Local cache timestamp only.
    val cachedAt: Long
)
