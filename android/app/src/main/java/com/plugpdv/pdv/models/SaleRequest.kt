package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class SaleRequest(
    val customerName: String? = null,
    val total: Double,
    val items: List<SaleItem>,
    @SerializedName("payment_method") val paymentMethod: String,
    val currency: String = "BRL",
    @SerializedName("caixa_session_id") var caixa_session_id: String? = null,
    @SerializedName("operator_id") var operatorId: String? = null,
    @SerializedName("operator_name") var operatorName: String? = null,
    @SerializedName("tax_amount") var taxAmount: Double = 0.0,
    @SerializedName("service_fee_amount") var serviceFeeAmount: Double = 0.0,
    @SerializedName("service_fee_kind") var serviceFeeKind: String? = null,
    @SerializedName("converted_total") var convertedTotal: Double? = null
)
