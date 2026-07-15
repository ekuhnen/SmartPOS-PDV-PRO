package com.plugpdv.pdv.models

data class ExchangeResponse(
    val moeda_principal: String? = null,
    val moedas: List<CurrencyRate> = emptyList(),
    // Keep the previous fields if they were used elsewhere
    val converted_amount: Double = 0.0,
    val rate: Double = 0.0,
    val from: String? = null,
    val to: String? = null
) {
    data class CurrencyRate(
        val codigo: String,
        val taxa: Double
    )
}
