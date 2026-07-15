package com.plugpdv.pdv.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class TaxResponse(
    val taxes: List<TaxRate>? = null,
    @SerializedName("service_fee") val serviceFee: ServiceFeeConfig? = null
)
