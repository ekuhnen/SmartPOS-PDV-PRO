package com.plugpdv.pdv.repository

import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.api.dto.DashboardReportDto
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.utils.MoneyDecimal
import javax.inject.Inject
import javax.inject.Singleton

enum class DateFilterOption { TODAY, YESTERDAY, ALL_TIME }

@Singleton
class ReportRepository @Inject constructor(private val apiService: PosApiService) {
    suspend fun getReportResult(token: String, dateOption: DateFilterOption, limit: Int = DEFAULT_PAGE_SIZE, offset: Int = 0, branchId: String? = null): Result<DashboardReport> =
        runCatching { getReport(token, dateOption, limit, offset, branchId) }

    suspend fun getReport(token: String, dateOption: DateFilterOption, limit: Int = DEFAULT_PAGE_SIZE, offset: Int = 0, branchId: String? = null): DashboardReport =
        apiService.getDashboardReport(bearer(token), dateOption.name, branchId = branchId, limit = limit, offset = offset).toDomain(limit, offset)

    private fun DashboardReportDto.toDomain(requestedLimit: Int, requestedOffset: Int): DashboardReport {
        fun money(amount: java.math.BigDecimal?, currency: String?): DashboardMoney? {
            val code = currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
            return amount?.let { DashboardMoney(MoneyDecimal.toMinorUnits(it, code), code) }
        }
        val mappedHistory = history?.items.orEmpty().mapNotNull { row ->
            DashboardHistoryItem(row.saleId.orEmpty(), row.createdAt, money(row.total, row.currency) ?: return@mapNotNull null, row.paymentMethod, row.operatorName)
        }
        val pageLimit = history?.limit ?: requestedLimit
        val pageOffset = history?.offset ?: requestedOffset
        return DashboardReport(
            sales = (totalsByCurrency ?: completedSales?.byCurrency).orEmpty().mapNotNull { row -> money(row.total, row.currency)?.let { DashboardSalesTotal(it, row.count ?: 0) } },
            payments = paymentsByMethod.orEmpty().mapNotNull { row ->
                val method = row.forma?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                money(row.total, row.currency)?.let { DashboardPaymentTotal(method, it, row.count ?: 0) }
            },
            openReceivables = openReceivables.orEmpty().mapNotNull { row -> money(row.remaining, row.currency)?.let { DashboardOpenReceivable(it, row.comandas ?: 0) } },
            cashOperations = cashOperations.orEmpty().mapNotNull { row ->
                val type = row.tipo?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                money(row.total, row.currency)?.let { DashboardCashOperation(type, it, row.count ?: 0) }
            },
            history = mappedHistory,
            pagination = DashboardPagination(pageLimit, pageOffset, null, mappedHistory.size == pageLimit),
            hasCanonicalTotal = notes?.grandTotal != null && !notes.grandTotal.isJsonNull
        )
    }

    private fun bearer(token: String) = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"

    companion object { const val DEFAULT_PAGE_SIZE = 50 }
}
