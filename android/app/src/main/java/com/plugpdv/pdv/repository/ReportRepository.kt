package com.plugpdv.pdv.repository

import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.api.dto.DashboardReportDto
import com.plugpdv.pdv.models.*
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
        fun money(amount: Long?, currency: String?): DashboardMoney? {
            val code = currency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
            return amount?.let { DashboardMoney(it, code) }
        }
        val mappedHistory = history.orEmpty().mapNotNull { row ->
            DashboardHistoryItem(row.id.orEmpty(), row.createdAt, money(row.amountMinor, row.currency) ?: return@mapNotNull null, row.paymentMethod, row.operator)
        }
        val pageLimit = pagination?.limit ?: requestedLimit
        val pageOffset = pagination?.offset ?: requestedOffset
        val pageTotal = pagination?.total
        return DashboardReport(
            sales = salesByCurrency.orEmpty().mapNotNull { row -> money(row.amountMinor, row.currency)?.let { DashboardSalesTotal(it, row.count ?: 0) } },
            payments = paymentsByMethod.orEmpty().mapNotNull { row ->
                val method = row.method?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                money(row.amountMinor, row.currency)?.let { DashboardPaymentTotal(method, it, row.count ?: 0) }
            },
            openReceivables = openReceivables.orEmpty().mapNotNull { row -> money(row.amountMinor, row.currency)?.let { DashboardOpenReceivable(it, row.count ?: 0) } },
            cashOperations = cashOperations.orEmpty().mapNotNull { row ->
                val type = row.type?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                money(row.amountMinor, row.currency)?.let { DashboardCashOperation(type, it, row.count ?: 0) }
            },
            history = mappedHistory,
            pagination = DashboardPagination(pageLimit, pageOffset, pageTotal, pagination?.hasMore ?: (pageTotal?.let { pageOffset + mappedHistory.size < it } ?: (mappedHistory.size == pageLimit))),
            hasCanonicalTotal = canonical != null && !canonical.isJsonNull
        )
    }

    private fun bearer(token: String) = if (token.startsWith("Bearer ", ignoreCase = true)) token else "Bearer $token"

    companion object { const val DEFAULT_PAGE_SIZE = 50 }
}
