package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

data class SaleRequest(
    val customerName: String? = null,
    val total: BigDecimal,
    val items: List<SaleItem>,
    @SerializedName("payment_method") val paymentMethod: String,
    val currency: String = "BRL",
    @SerializedName("payment_currency") val paymentCurrency: String? = null,
    @SerializedName("exchange_rates_snapshot") val exchangeRatesSnapshot: Map<String, String>? = null,
    @SerializedName("caixa_session_id") var caixa_session_id: String? = null,
    @SerializedName("operator_id") var operatorId: String? = null,
    @SerializedName("operator_name") var operatorName: String? = null,
    @SerializedName("tax_amount") var taxAmount: BigDecimal = BigDecimal.ZERO,
    @SerializedName("service_fee_amount") var serviceFeeAmount: BigDecimal = BigDecimal.ZERO,
    @SerializedName("service_fee_kind") var serviceFeeKind: String? = null,
    @SerializedName("converted_total") var convertedTotal: BigDecimal? = null
) {
    constructor(
        customerName: String? = null,
        total: Double,
        items: List<SaleItem>,
        paymentMethod: String,
        currency: String = "BRL",
        paymentCurrency: String? = null,
        exchangeRatesSnapshot: Map<String, String>? = null,
        caixa_session_id: String? = null,
        operatorId: String? = null,
        operatorName: String? = null,
        taxAmount: Double = 0.0,
        serviceFeeAmount: Double = 0.0,
        serviceFeeKind: String? = null,
        convertedTotal: Double? = null
    ) : this(
        customerName = customerName,
        total = BigDecimal.valueOf(total),
        items = items,
        paymentMethod = paymentMethod,
        currency = currency,
        paymentCurrency = paymentCurrency,
        exchangeRatesSnapshot = exchangeRatesSnapshot,
        caixa_session_id = caixa_session_id,
        operatorId = operatorId,
        operatorName = operatorName,
        taxAmount = BigDecimal.valueOf(taxAmount),
        serviceFeeAmount = BigDecimal.valueOf(serviceFeeAmount),
        serviceFeeKind = serviceFeeKind,
        convertedTotal = convertedTotal?.let { BigDecimal.valueOf(it) }
    )
}
