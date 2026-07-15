package com.plugpdv.pdv.models

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

@Keep
data class ServiceFeeConfig(
    @SerializedName("fixed_enabled") val fixedEnabled: Boolean = false,
    @SerializedName("fixed_percent") val fixedPercent: Double = 0.0,
    @SerializedName("allow_override") val allowOverride: Boolean = false
)
