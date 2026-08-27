package com.plugpdv.pdv.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tables",
    indices = [
        Index(value = ["sectorId", "number"], unique = true),
        Index(value = ["status"])
    ]
)
data class TableEntity(
    @PrimaryKey
    val id: String,                     // UUID ou ID da mesa
    val number: Int,                    // Número da mesa (ex: 1, 2, 3)
    val status: String,                 // "AVAILABLE", "OCCUPIED", "RESERVED"
    val sectorName: String = "",
    val sectorId: String = "",
    val customerName: String? = null,
    val comandaId: String? = null,
    val peopleCount: Int = 1,
    val totalBalance: Double = 0.0,     // Saldo total acumulado em BRL base
    val paidAmount: Double = 0.0,       // Valor já pago (parcial) em BRL base
    val pendingBalance: Double = 0.0,    // Saldo a pagar (totalBalance - paidAmount) em BRL base
    val itemsJson: String = "[]",       // Lista de itens serializada como JSON
    val updatedAt: Long = System.currentTimeMillis()
)
