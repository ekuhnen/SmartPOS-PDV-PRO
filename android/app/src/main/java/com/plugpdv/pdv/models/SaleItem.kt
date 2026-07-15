package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class SaleItem(
    @SerializedName("product_id") val productId: String,
    @SerializedName(value = "product_name", alternate = ["name", "nome"]) val productName: String? = null,
    val quantity: Int,
    val price: Double
)
