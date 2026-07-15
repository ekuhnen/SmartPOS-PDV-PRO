package com.plugpdv.pdv.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "local_sales")
data class LocalSaleEntity(
    @PrimaryKey
    val localId: String,           // UUID gerado localmente
    val apiId: String? = null,     // ID retornado pela API após sync
    val timestamp: Long,           // System.currentTimeMillis()
    val total: Double,
    val currency: String,
    val paymentMethod: String,
    val operatorId: String? = null,
    val operatorName: String? = null,
    val sessionId: String? = null,
    val itemsJson: String,         // Lista de SaleItem serializada como JSON
    val syncedToApi: Boolean = false
)
