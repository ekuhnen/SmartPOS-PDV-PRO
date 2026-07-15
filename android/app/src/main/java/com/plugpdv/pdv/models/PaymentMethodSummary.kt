package com.plugpdv.pdv.models

data class PaymentMethodSummary(
    val name: String,
    val total: Double,
    val iconRes: Int,
    val currencyCode: String? = null
)
