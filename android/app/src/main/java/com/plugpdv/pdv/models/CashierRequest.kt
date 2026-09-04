package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

data class CountedCashRequest(val currency: String,
    @SerializedName("counted_amount") val countedAmount: BigDecimal?)

data class CashierRequest(
    val action: String, val valor: BigDecimal? = null, val moeda: String? = null,
    @SerializedName("session_id") val sessionId: String? = null,
    val observacao: String? = null, val reference: String? = null,
    @SerializedName("counted_by_currency") val countedByCurrency: List<CountedCashRequest>? = null
)
