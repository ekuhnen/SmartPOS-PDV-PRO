package com.plugpdv.pdv.models

data class DashboardMoney(val amountMinor: Long, val currency: String)
data class DashboardSalesTotal(val money: DashboardMoney, val count: Int)
data class DashboardPaymentTotal(val method: String, val money: DashboardMoney, val count: Int)
data class DashboardOpenReceivable(val money: DashboardMoney, val count: Int)
data class DashboardCashOperation(val type: String, val money: DashboardMoney, val count: Int)
data class DashboardHistoryItem(val id: String, val createdAt: String?, val money: DashboardMoney, val paymentMethod: String?, val operator: String?)
data class DashboardPagination(val limit: Int, val offset: Int, val total: Int?, val hasMore: Boolean)
data class DashboardDisplayTotal(val label: String, val money: DashboardMoney)

data class DashboardReport(
    val sales: List<DashboardSalesTotal>,
    val payments: List<DashboardPaymentTotal>,
    val openReceivables: List<DashboardOpenReceivable>,
    val cashOperations: List<DashboardCashOperation>,
    val history: List<DashboardHistoryItem>,
    val pagination: DashboardPagination,
    val hasCanonicalTotal: Boolean
) {
    val isEmpty: Boolean get() = sales.isEmpty() && payments.isEmpty() && openReceivables.isEmpty() && cashOperations.isEmpty() && history.isEmpty()
}
