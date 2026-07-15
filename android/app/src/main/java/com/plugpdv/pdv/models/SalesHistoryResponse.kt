package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class SalesHistoryResponse(
    @SerializedName("sales") val sales: List<SaleHistoryItem>? = null,
    @SerializedName("data") val data: List<SaleHistoryItem>? = null,
    @SerializedName("items") val items: List<SaleHistoryItem>? = null
)
