package com.plugpdv.pdv.models

import java.io.Serializable

data class Table(
    var id: String? = null,
    var number: Int = 0,
    var status: String = Status.AVAILABLE,
    var current_order_id: String? = null,
    var total: Double = 0.0,
    var paidAmount: Double = 0.0,
    var people_count: Int = 1,
    var customerName: String = "",
    var comandaId: String? = null,
    var sectorName: String = "",
    var sectorId: String = "",
    var items: MutableList<TableItem> = mutableListOf()
) : Serializable {

    object Status {
        const val AVAILABLE = "AVAILABLE"
        const val OCCUPIED = "OCCUPIED"
        const val BILLING = "BILLING"
        const val RESERVED = "RESERVED"
    }

    fun getPendingBalance(): Double {
        var itemsTotal = 0.0
        items.filter { !it.removed }.forEach {
            itemsTotal += (it.product.selling_price ?: 0.0) * it.quantity
        }
        return itemsTotal - paidAmount
    }

    fun calculateTotal(): Double {
        total = items.filter { !it.removed }.sumOf { (it.product.selling_price ?: 0.0) * it.quantity }
        return total
    }
}
