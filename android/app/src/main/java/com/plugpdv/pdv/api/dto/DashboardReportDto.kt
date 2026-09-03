package com.plugpdv.pdv.api.dto

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName

/** Transport-only representation of GET api-relatorios. */
data class DashboardReportDto(
    @SerializedName(value = "salesByCurrency", alternate = ["sales_by_currency"])
    val salesByCurrency: List<CurrencyTotalDto>? = null,
    @SerializedName(value = "paymentsByMethod", alternate = ["payments_by_method"])
    val paymentsByMethod: List<PaymentTotalDto>? = null,
    @SerializedName(value = "openReceivables", alternate = ["open_receivables"])
    val openReceivables: List<OpenReceivableDto>? = null,
    @SerializedName(value = "cashOperations", alternate = ["cash_operations"])
    val cashOperations: List<CashOperationDto>? = null,
    val history: List<ReportHistoryDto>? = null,
    val pagination: ReportPaginationDto? = null,
    val canonical: JsonElement? = null
)

data class CurrencyTotalDto(val currency: String?, @SerializedName(value = "amountMinor", alternate = ["amount_minor", "totalMinor", "total_minor"]) val amountMinor: Long?, val count: Int? = null)
data class PaymentTotalDto(@SerializedName(value = "method", alternate = ["paymentMethod", "payment_method"]) val method: String?, val currency: String?, @SerializedName(value = "amountMinor", alternate = ["amount_minor", "totalMinor", "total_minor"]) val amountMinor: Long?, val count: Int? = null)
data class OpenReceivableDto(val currency: String?, @SerializedName(value = "amountMinor", alternate = ["amount_minor", "remainingMinor", "remaining_minor"]) val amountMinor: Long?, val count: Int? = null)
data class CashOperationDto(@SerializedName(value = "type", alternate = ["operationType", "operation_type"]) val type: String?, val currency: String?, @SerializedName(value = "amountMinor", alternate = ["amount_minor", "totalMinor", "total_minor"]) val amountMinor: Long?, val count: Int? = null)
data class ReportHistoryDto(val id: String?, @SerializedName(value = "createdAt", alternate = ["created_at", "dateTime", "date_time"]) val createdAt: String?, val currency: String?, @SerializedName(value = "amountMinor", alternate = ["amount_minor", "totalMinor", "total_minor"]) val amountMinor: Long?, @SerializedName(value = "paymentMethod", alternate = ["payment_method"]) val paymentMethod: String?, @SerializedName(value = "operator", alternate = ["operatorName", "operator_name"]) val operator: String?)
data class ReportPaginationDto(val limit: Int? = null, val offset: Int? = null, val total: Int? = null, @SerializedName(value = "hasMore", alternate = ["has_more"]) val hasMore: Boolean? = null)
