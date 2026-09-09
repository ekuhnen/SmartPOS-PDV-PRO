package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

/** Request envelope used by the deployed terminal-sync capabilities action. */
data class CapabilitiesRequest(
    @SerializedName("action") val action: String = "capabilities"
)

data class CapabilitiesResponse(
    @SerializedName("currencies") val currencies: Map<String, CurrencyCapability> = emptyMap(),
    @SerializedName("base_currency") val baseCurrency: String? = null,
    @SerializedName("payment_methods") val paymentMethods: List<PaymentMethodCapability> = emptyList(),
    @SerializedName("payment_providers") val paymentProviders: List<PaymentProviderCapability>? = null,
    @SerializedName("default_payment_provider") val defaultPaymentProvider: String? = null,
    @SerializedName("allow_payment_provider_selection") val allowPaymentProviderSelection: Boolean? = null,
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
    @SerializedName("minor_unit_digits") val minorUnitDigits: Int = 2,
    @SerializedName("cash_rounding_mode") val cashRoundingMode: String = "HALF_EVEN"
)

data class PaymentMethodCapability(
    @SerializedName("code") val code: String,
    @SerializedName("name_key") val nameKey: String,
    @SerializedName("icon_url") val iconUrl: String? = null,
    @SerializedName("allowed_offline") val allowedOffline: Boolean = true,
    @SerializedName("requires_customer_tax_id") val requiresCustomerTaxId: Boolean = false
)

/**
 * Non-secret company policy for a concrete payment integration.
 * Credentials never belong in terminal capabilities or in the local cache.
 *
 * `supported_currencies == null` means that the capabilities payload did not
 * publish an additional provider-level currency restriction. An explicit empty
 * list means the provider is not authorized for any transaction currency.
 */
data class PaymentProviderCapability(
    @SerializedName("provider") val provider: String,
    @SerializedName("enabled") val enabled: Boolean = false,
    @SerializedName("supported_currencies") val supportedCurrencies: List<String>? = null
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
