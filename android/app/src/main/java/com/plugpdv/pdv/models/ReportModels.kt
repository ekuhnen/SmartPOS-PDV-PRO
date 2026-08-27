package com.plugpdv.pdv.models

data class ReportSummary(
    val isOfflineData: Boolean = false,
    val dateFilterLabel: String = "Hoje",
    val sales: List<SaleHistoryItem> = emptyList(),
    val occupiedTables: List<TableReportItem> = emptyList(),
    val paymentSummaries: List<PaymentMethodSummary> = emptyList(),
    val currencySummaries: List<PaymentMethodSummary> = emptyList(),
    val totalSalesAmountBrl: Double = 0.0,
    val totalPendingTablesAmountBrl: Double = 0.0,
    val totalSangriaAmountBrl: Double = 0.0
)

data class TableReportItem(
    val number: Int,
    val customerName: String? = null,
    val comandaId: String? = null,
    val totalAmountBrl: Double = 0.0,
    val paidAmountBrl: Double = 0.0,
    val pendingAmountBrl: Double = 0.0,
    val itemCount: Int = 0
)
