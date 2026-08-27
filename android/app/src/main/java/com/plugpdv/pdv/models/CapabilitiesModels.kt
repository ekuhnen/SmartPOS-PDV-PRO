package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class CapabilitiesResponse(
    @SerializedName("currencies") val currencies: Map<String, CurrencyCapability> = emptyMap(),
    @SerializedName("payment_methods") val paymentMethods: List<PaymentMethodCapability> = emptyList(),
    @SerializedName("offline_policies") val offlinePolicies: Map<String, OfflinePolicyCapability> = emptyMap(),
    @SerializedName("taxes") val taxes: List<TaxRate> = emptyList()
)

data class CurrencyCapability(
    @SerializedName("currency_code") val currencyCode: String,
    @SerializedName("symbol") val symbol: String,
    @SerializedName("symbol_position") val symbolPosition: String = "PREFIX", // "PREFIX" ou "SUFFIX"
    @SerializedName("thousands_separator") val thousandsSeparator: String = ".",
    @SerializedName("decimal_separator") val decimalSeparator: String = ",",
    @SerializedName("display_decimals") val displayDecimals: Int = 2,
    @SerializedName("cash_rounding_mode") val cashRoundingMode: String = "HALF_EVEN"
)

data class PaymentMethodCapability(
    @SerializedName("code") val code: String,
    @SerializedName("name_key") val nameKey: String,
    @SerializedName("icon_url") val iconUrl: String? = null,
    @SerializedName("allowed_offline") val allowedOffline: Boolean = true,
    @SerializedName("requires_customer_tax_id") val requiresCustomerTaxId: Boolean = false
)

data class OfflinePolicyCapability(
    @SerializedName("operation_type") val operationType: String,
    @SerializedName("allowed") val allowed: Boolean = true,
    @SerializedName("max_amount") val maxAmount: Long? = null,
    @SerializedName("max_offline_hours") val maxOfflineHours: Int = 24
)

data class ApiErrorEnvelope(
    @SerializedName("code") val code: String,
    @SerializedName("message_key") val messageKey: String,
    @SerializedName("details") val details: Map<String, Any>? = null,
    @SerializedName("retriable") val retriable: Boolean = true
)
