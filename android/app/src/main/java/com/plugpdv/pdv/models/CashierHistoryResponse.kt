package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class CashierHistoryResponse(
    @SerializedName("history") val history: List<CashierSession>? = null,
    @SerializedName("data") val data: List<CashierSession>? = null,
    @SerializedName("operacoes") val operacoes: List<CashierSession>? = null
)
