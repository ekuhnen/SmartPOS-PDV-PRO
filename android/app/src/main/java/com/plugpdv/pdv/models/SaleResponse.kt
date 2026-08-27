package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class SaleResponse(
    @SerializedName("id") val id: String? = null,
    @SerializedName("sale_id") val saleId: String? = null,
    val status: String? = null,
    @SerializedName("service_fee") val serviceFee: Double? = null,
    @SerializedName("service_fee_kind") val serviceFeeKind: String? = null
) {
    val realId: String? get() = id ?: saleId
}
