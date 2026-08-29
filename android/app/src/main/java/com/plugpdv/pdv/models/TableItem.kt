package com.plugpdv.pdv.models

import java.io.Serializable

data class TableItem(
    var id: String? = null,
    var product: Product,
    var quantity: Int = 1,
    var paidQuantity: Int = 0,
    var isPaid: Boolean = false,
    var observation: String? = null,
    var status: String = "PENDING",
    var removed: Boolean = false,
    var removalReason: String? = null,
    var timestamp: Long = System.currentTimeMillis(),
    var serverIds: MutableList<String>? = null
) : Serializable

data class TableItemPayment(
    val item: TableItem,
    var selected: Boolean = false,
    var selectedQuantity: Int = item.quantity - item.paidQuantity
) : Serializable
