package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class CashierRequest(
    @SerializedName(value = "action", alternate = ["tipo"]) val action: String,
    @SerializedName("valor") val valor: Double,
    @SerializedName("moeda") var moeda: String = "BRL",
    @SerializedName("session_id") var session_id: String? = null,
    @SerializedName("observacao") var observacao: String? = null,
    @SerializedName("resumo") var resumo: Map<String, Double>? = null
)
