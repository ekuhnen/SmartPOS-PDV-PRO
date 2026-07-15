package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class SaleResponse(
    val id: String? = null,
    val status: String? = null,
    @SerializedName("service_fee") val serviceFee: Double? = null,
    @SerializedName("service_fee_kind") val serviceFeeKind: String? = null
)
