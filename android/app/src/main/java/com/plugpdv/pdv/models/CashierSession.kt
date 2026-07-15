package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class CashierSession(
    val id: String? = null,
    val tipo: String? = null,
    val valor: Double = 0.0,
    @SerializedName("caixa_session_id") val caixa_session_id: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
)
