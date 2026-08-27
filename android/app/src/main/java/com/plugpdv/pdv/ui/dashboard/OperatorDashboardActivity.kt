package com.plugpdv.pdv.ui.dashboard

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.plugpdv.pdv.R
import com.plugpdv.pdv.databinding.ActivityOperatorDashboardBinding
import com.plugpdv.pdv.models.PaymentMethodSummary
import com.plugpdv.pdv.repository.DateFilterOption
import com.plugpdv.pdv.ui.BaseActivity
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.PrinterHelper
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class OperatorDashboardActivity : BaseActivity() {
    private lateinit var binding: ActivityOperatorDashboardBinding
    private val viewModel: OperatorDashboardViewModel by viewModels()
    private lateinit var saleAdapter: SaleHistoryAdapter
    private lateinit var paymentAdapter: PaymentMethodAdapter
    private lateinit var currencyAdapter: PaymentMethodAdapter
    private lateinit var occupiedTableAdapter: OccupiedTableReportAdapter
    private var token: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOperatorDashboardBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { finish() }

        token = intent.getStringExtra("ACCESS_TOKEN")
        val sessionId = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).getString("SESSION_ID", null)

        saleAdapter = SaleHistoryAdapter(mutableListOf())
        binding.rvHistory.layoutManager = LinearLayoutManager(this)
        binding.rvHistory.adapter = saleAdapter

        paymentAdapter = PaymentMethodAdapter(emptyList())
        binding.rvPaymentMethods.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvPaymentMethods.adapter = paymentAdapter

        currencyAdapter = PaymentMethodAdapter(emptyList())
        binding.rvCurrencies.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        binding.rvCurrencies.adapter = currencyAdapter

        occupiedTableAdapter = OccupiedTableReportAdapter(emptyList())
        binding.rvOccupiedTables.layoutManager = LinearLayoutManager(this)
        binding.rvOccupiedTables.adapter = occupiedTableAdapter

        setupDateFilterChips()

        binding.toolbar.inflateMenu(R.menu.menu_dashboard)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_print) {
                printAuditReport()
                true
            } else false
        }

        binding.fabCurrency.setOnClickListener { showCurrencySelector { updateUI() } }

        token?.let { viewModel.fetchData(it, sessionId) }

        observeViewModel()
    }

    private fun setupDateFilterChips() {
        binding.chipGroupDate.setOnCheckedChangeListener { _, checkedId ->
            when (checkedId) {
                R.id.chipToday -> viewModel.setDateFilter(DateFilterOption.TODAY)
                R.id.chipYesterday -> viewModel.setDateFilter(DateFilterOption.YESTERDAY)
                R.id.chipAllTime -> viewModel.setDateFilter(DateFilterOption.ALL_TIME)
            }
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.reportSummary.observe(this) { summary ->
            if (summary != null) {
                binding.cardOfflineBanner.visibility = if (summary.isOfflineData) View.VISIBLE else View.GONE

                val cm = CurrencyManager.getInstance()
                binding.tvTotalVendas.text = cm.format(summary.totalSalesAmountBrl)
                binding.tvTotalSangria.text = "Sangria: ${cm.format(summary.totalSangriaAmountBrl)}"

                // Mesas em aberto
                occupiedTableAdapter.updateData(summary.occupiedTables)
                binding.labelTablesHeader.visibility = if (summary.occupiedTables.isNotEmpty()) View.VISIBLE else View.GONE
                binding.rvOccupiedTables.visibility = if (summary.occupiedTables.isNotEmpty()) View.VISIBLE else View.GONE

                // Meios de pagamento
                val pSummaries = summary.paymentSummaries.map { pm ->
                    val icon = if (pm.name.contains("DINHEIRO") || pm.name.contains("CASH")) R.drawable.ic_attach_money
                    else if (pm.name.contains("PIX")) R.drawable.ic_dashboard_modern
                    else R.drawable.ic_credit_card
                    PaymentMethodSummary(pm.name, pm.total, icon)
                }
                paymentAdapter.updateData(pSummaries)

                // Moedas
                val cSummaries = summary.currencySummaries.map { cs ->
                    PaymentMethodSummary(cs.name, cs.total, R.drawable.ic_attach_money, cs.currencyCode ?: "BRL")
                }
                currencyAdapter.updateData(cSummaries)

                // Vendas
                saleAdapter.updateData(summary.sales)
            }
        }
    }

    private fun updateUI() {
        viewModel.reportSummary.value?.let { summary ->
            val cm = CurrencyManager.getInstance()
            binding.tvTotalVendas.text = cm.format(summary.totalSalesAmountBrl)
        }
    }

    private fun printAuditReport() {
        val summary = viewModel.reportSummary.value
        if (summary == null || (summary.sales.isEmpty() && summary.occupiedTables.isEmpty())) {
            Toast.makeText(this, "Nenhum dado para imprimir no relatório", Toast.LENGTH_SHORT).show()
            return
        }

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val operatorEmail = prefs.getString(Constants.EMAIL, null)

        PrinterHelper.printAuditReport(this, summary, operatorEmail)
        Toast.makeText(this, "Imprimindo relatório de auditoria...", Toast.LENGTH_SHORT).show()
    }
}
