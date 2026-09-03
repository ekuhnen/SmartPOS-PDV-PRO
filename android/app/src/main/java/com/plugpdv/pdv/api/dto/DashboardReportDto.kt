package com.plugpdv.pdv.api.dto

import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

/** Exact transport representation of the deployed dashboard_v1 response. */
data class DashboardReportDto(
    val protocol: String?,
    val period: ReportPeriodDto?,
    val completedSales: CompletedSalesDto?,
    val paymentsByMethod: List<PaymentTotalDto>?,
    val openReceivables: List<OpenReceivableDto>?,
    val cashOperations: List<CashOperationDto>?,
    val history: ReportHistoryPageDto?,
    val notes: ReportNotesDto?,
    @SerializedName("totals_by_currency") val totalsByCurrency: List<CurrencyTotalDto>? = null,
    @SerializedName("total_revenue") val totalRevenue: BigDecimal? = null,
    @SerializedName("total_revenue_currency") val totalRevenueCurrency: String? = null
)

data class ReportPeriodDto(
    val requested: String?,
    val timezone: String?,
    val localFrom: String?,
    val localTo: String?,
    val fromTs: String?,
    val toTs: String?,
    val resolvedBy: String?
)

data class CompletedSalesDto(val byCurrency: List<CurrencyTotalDto>?, val coverage: SalesCoverageDto?)
data class SalesCoverageDto(
    val transactionCurrencyAuthoritativeCount: Int?,
    val canonicalBaseAvailableCount: Int?,
    val canonicalBaseUnavailableCount: Int?
)
data class CurrencyTotalDto(val currency: String?, val count: Int?, val total: BigDecimal?, val canonical: JsonElement?)

data class PaymentTotalDto(
    val paymentCode: String?,
    val forma: String?,
    val currency: String?,
    val count: Int?,
    val total: BigDecimal?,
    val excluded: ExcludedPaymentCountsDto?
)
data class ExcludedPaymentCountsDto(val registrado: Int?, val estornado: Int?)

data class OpenReceivableDto(
    val currency: String?,
    val comandas: Int?,
    val totalLiquido: BigDecimal?,
    val totalPago: BigDecimal?,
    val remaining: BigDecimal?,
    val requiresReconciliationCount: Int?,
    val source: String?
)

data class CashOperationDto(
    val tipo: String?,
    val currency: String?,
    val currencyAuthority: String?,
    val count: Int?,
    val total: BigDecimal?
)

data class ReportHistoryPageDto(val limit: Int?, val offset: Int?, val items: List<ReportHistoryDto>?)
data class ReportHistoryDto(
    val saleId: String?,
    val createdAt: String?,
    val localDate: String?,
    val total: BigDecimal?,
    val currency: String?,
    val paymentCode: String?,
    val paymentMethod: String?,
    val canonical: JsonElement?,
    val comandaId: String?,
    val operatorName: String?
)
data class ReportNotesDto(val grandTotal: JsonElement?, val grandTotalReason: String?)
