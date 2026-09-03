package com.plugpdv.pdv.ui.dashboard

import android.os.Bundle
import android.view.View
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.plugpdv.pdv.R
import com.plugpdv.pdv.databinding.ActivityOperatorDashboardBinding
import com.plugpdv.pdv.models.DashboardDisplayTotal
import com.plugpdv.pdv.models.DashboardReport
import com.plugpdv.pdv.repository.DateFilterOption
import com.plugpdv.pdv.ui.BaseActivity
import com.plugpdv.pdv.utils.CurrencyManager
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class OperatorDashboardActivity : BaseActivity() {
    private lateinit var binding: ActivityOperatorDashboardBinding
    private val viewModel: OperatorDashboardViewModel by viewModels()
    private val historyAdapter = SaleHistoryAdapter()
    private val paymentAdapter = PaymentMethodAdapter(emptyList())
    private val salesAdapter = PaymentMethodAdapter(emptyList())
    private val receivablesAdapter = PaymentMethodAdapter(emptyList())
    private val cashAdapter = PaymentMethodAdapter(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOperatorDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.fabCurrency.visibility = View.GONE

        binding.rvHistory.layoutManager = LinearLayoutManager(this); binding.rvHistory.adapter = historyAdapter
        binding.rvPaymentMethods.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false); binding.rvPaymentMethods.adapter = paymentAdapter
        binding.rvCurrencies.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false); binding.rvCurrencies.adapter = salesAdapter
        binding.rvOccupiedTables.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false); binding.rvOccupiedTables.adapter = receivablesAdapter
        binding.rvCashOperations.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false); binding.rvCashOperations.adapter = cashAdapter

        binding.chipGroupDate.setOnCheckedChangeListener { _, id ->
            when (id) {
                R.id.chipToday -> viewModel.setDateFilter(DateFilterOption.TODAY)
                R.id.chipYesterday -> viewModel.setDateFilter(DateFilterOption.YESTERDAY)
                R.id.chipAllTime -> viewModel.setDateFilter(DateFilterOption.ALL_TIME)
            }
        }
        binding.buttonLoadMore.setOnClickListener { viewModel.loadNextPage() }
        viewModel.state.observe(this, ::render)
        viewModel.fetchData(intent.getStringExtra("ACCESS_TOKEN"), null)
    }

    private fun render(state: DashboardUiState) {
        binding.progressBar.visibility = if (state is DashboardUiState.Loading || (state is DashboardUiState.Success && state.loadingMore)) View.VISIBLE else View.GONE
        binding.cardOfflineBanner.visibility = if (state is DashboardUiState.Error) View.VISIBLE else View.GONE
        if (state is DashboardUiState.Error) binding.tvOfflineMessage.setText(R.string.dashboard_report_error)
        if (state is DashboardUiState.Success) renderReport(state.report)
    }

    private fun renderReport(report: DashboardReport) {
        val cm = CurrencyManager.getInstance()
        binding.tvTotalVendas.text = report.sales.joinToString("\n") { cm.formatMinorUnits(it.money.amountMinor, it.money.currency) }
            .ifBlank { getString(R.string.amount_placeholder) }
        binding.tvTotalSangria.visibility = View.GONE
        salesAdapter.updateData(report.sales.map { DashboardDisplayTotal(it.money.currency, it.money) })
        paymentAdapter.updateData(report.payments.map { DashboardDisplayTotal(it.method, it.money) })
        receivablesAdapter.updateData(report.openReceivables.map { DashboardDisplayTotal(it.money.currency, it.money) })
        cashAdapter.updateData(report.cashOperations.map { DashboardDisplayTotal(it.type, it.money) })
        historyAdapter.updateData(report.history)
        binding.labelTablesHeader.visibility = if (report.openReceivables.isEmpty()) View.GONE else View.VISIBLE
        binding.rvOccupiedTables.visibility = binding.labelTablesHeader.visibility
        binding.labelCashOperations.visibility = if (report.cashOperations.isEmpty()) View.GONE else View.VISIBLE
        binding.rvCashOperations.visibility = binding.labelCashOperations.visibility
        binding.buttonLoadMore.visibility = if (report.pagination.hasMore) View.VISIBLE else View.GONE
        binding.tvEmptyReport.visibility = if (report.isEmpty) View.VISIBLE else View.GONE
    }
}
