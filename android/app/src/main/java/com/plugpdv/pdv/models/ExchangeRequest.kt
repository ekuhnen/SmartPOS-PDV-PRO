package com.plugpdv.pdv.models

data class ExchangeRequest(
    val action: String? = null,
    val amount: Double = 0.0,
    val from: String? = null,
    val to: String? = null
)
