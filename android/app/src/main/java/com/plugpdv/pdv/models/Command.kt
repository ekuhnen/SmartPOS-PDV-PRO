package com.plugpdv.pdv.models

data class Command(
    val id: String = "",
    val code: String = "",
    var status: String = "AVAILABLE", // AVAILABLE, OCCUPIED, BILLING
    var current_order_id: String? = null,
    var total: Double = 0.0
)
