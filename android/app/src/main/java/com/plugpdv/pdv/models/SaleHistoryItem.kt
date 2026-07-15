package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class SaleHistoryItem(
    val id: String? = null,
    val total: Double = 0.0,
    @SerializedName("payment_method") val paymentMethod: String? = null,
    @SerializedName("currency") val currency: String? = null,
    @SerializedName("converted_total") val convertedTotal: Double? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("items") val items: List<SaleItem>? = null,
    @SerializedName("service_fee") val serviceFee: Double? = null,
    @SerializedName("service_fee_kind") val serviceFeeKind: String? = null
)
