package com.plugpdv.pdv.ui.dashboard

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.plugpdv.pdv.R
import com.plugpdv.pdv.databinding.ActivityOperatorDashboardBinding
import com.plugpdv.pdv.models.PaymentMethodSummary
import com.plugpdv.pdv.ui.BaseActivity
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.PrinterHelper
import dagger.hilt.android.AndroidEntryPoint
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class OperatorDashboardActivity : BaseActivity() {
    private lateinit var binding: ActivityOperatorDashboardBinding
    private val viewModel: OperatorDashboardViewModel by viewModels()
    private lateinit var saleAdapter: SaleHistoryAdapter
    private lateinit var paymentAdapter: PaymentMethodAdapter
    private lateinit var currencyAdapter: PaymentMethodAdapter
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

        binding.toolbar.inflateMenu(R.menu.menu_dashboard)
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_print) {
                printHistory()
                true
            } else false
        }

        binding.fabCurrency.setOnClickListener { showCurrencySelector { updateUI() } }

        if (sessionId == null) {
            Toast.makeText(this, R.string.no_open_cashier, Toast.LENGTH_SHORT).show()
        } else {
            token?.let { viewModel.fetchData(it, sessionId) }
        }

        observeViewModel()
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.sales.observe(this) { sales ->
            saleAdapter.updateData(sales)
            updateUI()
        }

        viewModel.operations.observe(this) {
            updateUI()
        }
    }

    private fun updateUI() {
        val sales = viewModel.sales.value ?: emptyList()
        val operations = viewModel.operations.value ?: emptyList()

        var totalVendas = 0.0
        var totalCash = 0.0
        var totalDigital = 0.0
        var totalSangria = 0.0

        sales.forEach { sale ->
            totalVendas += sale.total
            val method = sale.paymentMethod?.uppercase() ?: ""
            if (method.contains("DINHEIRO") || method.contains("CASH") || method.contains("EFECTIVO")) {
                totalCash += sale.total
            } else {
                totalDigital += sale.total
            }
        }

        operations.forEach { op ->
            val tipo = op.tipo?.uppercase() ?: ""
            if (tipo.contains("SANGRIA") || tipo.contains("WITHDRAWAL")) {
                totalSangria += op.valor
            }
        }

        val cm = CurrencyManager.getInstance()
        binding.tvTotalVendas.text = cm.format(totalVendas)
        binding.tvTotalSangria.text = "Sangria: ${cm.format(totalSangria)}"

        // Update Payment Tiles
        val summaries = mutableListOf<PaymentMethodSummary>()
        val methods = sales.map { it.paymentMethod?.uppercase() ?: "OUTROS" }.distinct()
        methods.forEach { method ->
            val mTotal = sales.filter { (it.paymentMethod?.uppercase() ?: "OUTROS") == method }.sumOf { it.total }
            val icon = if (method.contains("DINHEIRO") || method.contains("CASH")) R.drawable.ic_attach_money
                       else if (method.contains("PIX")) R.drawable.ic_dashboard_modern
                       else R.drawable.ic_credit_card
            summaries.add(PaymentMethodSummary(method, mTotal, icon))
        }
        paymentAdapter.updateData(summaries)

        // Update Currency Tiles
        val currencySummaries = mutableListOf<PaymentMethodSummary>()
        val currencies = sales.map { it.currency ?: "BRL" }.distinct()
        currencies.forEach { curr ->
            val cTotal = sales.filter { (it.currency ?: "BRL") == curr }
                             .sumOf { it.convertedTotal ?: it.total }
            
            val icon = when (curr.uppercase()) {
                "PYG", "GS" -> R.drawable.ic_attach_money
                "BRL", "R$" -> R.drawable.ic_attach_money
                "USD" -> R.drawable.ic_attach_money
                else -> R.drawable.ic_attach_money
            }
            
            currencySummaries.add(PaymentMethodSummary("Total $curr", cTotal, icon, curr))
        }
        currencyAdapter.updateData(currencySummaries)
    }

    private fun printHistory() {
        val sales = viewModel.sales.value ?: return
        if (sales.isEmpty()) {
            Toast.makeText(this, "Nenhuma venda para imprimir", Toast.LENGTH_SHORT).show()
            return
        }

        val sb = StringBuilder()
        sb.append("      RELATORIO DE VENDAS\n")
        sb.append("      -------------------\n\n")
        
        // Items Summary
        sb.append("PRODUTOS VENDIDOS:\n")
        val allItems = sales.flatMap { it.items ?: emptyList() }
        val grouped = allItems.groupBy { it.productName ?: "Prod: ${it.productId}" }
        grouped.forEach { (name, list) ->
            val qty = list.sumOf { it.quantity }
            sb.append(String.format("%-18s x%d\n", name.take(18), qty))
        }
        sb.append("---------------------------\n")

        // Totals by Currency
        sb.append("\nRESUMO POR MOEDA:\n")
        val cm = CurrencyManager.getInstance()
        val currencies = sales.map { it.currency ?: "BRL" }.distinct()
        currencies.forEach { curr ->
            val cTotal = sales.filter { (it.currency ?: "BRL") == curr }
                             .sumOf { it.convertedTotal ?: it.total }
            sb.append(String.format("%-10s %15s\n", curr, cm.formatExplicit(cTotal, curr)))
        }
        sb.append("---------------------------\n")

        // Totals by Method
        sb.append("\nRESUMO POR PAGAMENTO:\n")
        val methods = sales.map { it.paymentMethod?.uppercase() ?: "OUTROS" }.distinct()
        methods.forEach { method ->
            val mTotal = sales.filter { (it.paymentMethod?.uppercase() ?: "OUTROS") == method }.sumOf { it.total }
            sb.append(String.format("%-15s %10s\n", method.take(15), cm.format(mTotal)))
        }
        
        val total = sales.sumOf { it.total }
        sb.append("---------------------------\n")
        sb.append(String.format("%-15s %10s\n", "TOTAL GERAL", cm.format(total)))
        sb.append("\nData: ${java.text.SimpleDateFormat("dd/MM/yy HH:mm").format(Date())}\n\n\n\n")

        PrinterHelper.printReceipt(this, sb.toString())
        Toast.makeText(this, "Imprimindo relatório...", Toast.LENGTH_SHORT).show()
    }
}
