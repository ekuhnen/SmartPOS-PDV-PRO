package com.plugpdv.pdv.ui.sale

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import com.plugpdv.pdv.R
import com.plugpdv.pdv.databinding.ActivityCheckoutBinding
import com.plugpdv.pdv.ui.BaseActivity
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.PaymentResultStore
import com.plugpdv.pdv.utils.PrinterHelper
import dagger.hilt.android.AndroidEntryPoint
import android.view.View
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class CheckoutActivity : BaseActivity() {
    private lateinit var binding: ActivityCheckoutBinding
    private val viewModel: DirectCheckoutViewModel by viewModels()
    
    private var token: String? = null
    private var sessionId: String? = null
    private var operatorId: String? = null
    private var operatorName: String? = null

    /** Flag para evitar processar o mesmo resultado duas vezes */
    private var paymentProcessed = false
    private var pendingDirectSaleOperationId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCheckoutBinding.inflate(layoutInflater)
        setContentView(binding.root)

        token = intent.getStringExtra("ACCESS_TOKEN")
        val cartItems = intent.getSerializableExtra("CART_ITEMS") as? List<SaleViewModel.CartItem>

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        sessionId = prefs.getString(Constants.SESSION_ID, null)
        operatorId = prefs.getString(Constants.OPERATOR_ID, null)
        operatorName = prefs.getString(Constants.OPERATOR_NAME, null)

        pendingDirectSaleOperationId = savedInstanceState?.getString("PENDING_DIRECT_SALE_OP_ID")

        cartItems?.let { viewModel.init(it, token) }
        viewModel.restoreDurableRecovery()

        observeViewModel()

        binding.btnPayLink.setOnClickListener { startPaymentFlow() }
        binding.btnViewItems.setOnClickListener { showItemsDetail() }
        binding.fabCurrency.setOnClickListener {
            showCurrencySelector { viewModel.calculateTotals() }
        }

        // Verifica se voltou com falha no pagamento
        if (intent.getBooleanExtra("PAYMENT_FAILED", false)) {
            val msg = intent.getStringExtra("PAYMENT_MESSAGE") ?: "Pagamento não aprovado"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("PENDING_DIRECT_SALE_OP_ID", pendingDirectSaleOperationId)
    }

    override fun onResume() {
        super.onResume()
        checkPendingPaymentResult()
        viewModel.restoreDurableRecovery()
        updatePayButtonState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Verifica falha enviada via intent
        if (intent.getBooleanExtra("PAYMENT_FAILED", false)) {
            val msg = intent.getStringExtra("PAYMENT_MESSAGE") ?: "Pagamento não aprovado"
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
        checkPendingPaymentResult()
        viewModel.restoreDurableRecovery()
        updatePayButtonState()
    }

    /**
     * Verifica se o PaymentResultStore tem um resultado de pagamento aprovado pendente.
     * Chamado em onResume e onNewIntent para garantir que o resultado seja processado
     * independentemente de como o CheckoutActivity foi retomado.
     */
    internal fun checkPendingPaymentResult() {
        if (paymentProcessed) return
        val result = PaymentResultStore.consume()
        if (result != null && result.status.equals("APPROVED", ignoreCase = true)) {
            Log.d("CheckoutActivity", "Pagamento aprovado recebido via PaymentResultStore. method=${result.method}, requestId=${result.requestId}")
            val operationId = result.requestId ?: pendingDirectSaleOperationId
            if (!operationId.isNullOrEmpty()) {
                paymentProcessed = true
                val method = mapToApiMethod(result.method ?: "PIX")
                viewModel.finalizeApprovedSale(operationId, result.paymentId, method)
            } else {
                Log.e("CheckoutActivity", "PAGAMENTO_APROVADO_SEM_CHAVE: Impossível determinar chave de correlação K. Bloqueando de forma durável.")
                com.plugpdv.pdv.utils.DirectPaymentReconciliationStore.setMarker(
                    context = this,
                    reason = "APPROVED_WITHOUT_CORRELATION",
                    paymentId = result.paymentId,
                    method = result.method
                )
                updatePayButtonState()
                Toast.makeText(this, "Pagamento aprovado requer conciliação. Contate o suporte.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updatePayButtonState() {
        val hasDurableMarker = com.plugpdv.pdv.utils.DirectPaymentReconciliationStore.isReconciliationRequired(this)
        val isBlocked = hasDurableMarker || viewModel.isPaymentBlocked.value
        val canResume = !hasDurableMarker && viewModel.canResumeSameOperation.value
        val reason = if (hasDurableMarker) {
            "Pagamento aprovado requer conciliação"
        } else {
            viewModel.blockReason.value
        }

        if (isBlocked) {
            if (canResume) {
                binding.btnPayLink.isEnabled = !(viewModel.isLoading.value ?: false)
                binding.btnPayLink.text = "Retomar pagamento"
            } else {
                binding.btnPayLink.isEnabled = false
                if (!reason.isNullOrBlank()) {
                    binding.btnPayLink.text = reason
                }
            }
        } else {
            binding.btnPayLink.isEnabled = !(viewModel.isLoading.value ?: false)
            binding.btnPayLink.text = "Cobrar"
        }
    }

    private fun observeViewModel() {
        val cm = CurrencyManager.getInstance()
        
        viewModel.finalTotal.observe(this) { total ->
            binding.tvCheckoutTotal.text = cm.format(total)
        }

        viewModel.cartItems.observe(this) { items ->
            if (items.isNullOrEmpty()) {
                finish() // Volta pra tela de produtos automaticamente
            }
        }

        viewModel.baseTotal.observe(this) { updateTaxBreakdown() }
        viewModel.activeTaxes.observe(this) { updateTaxBreakdown() }
        viewModel.serviceFeeAmount.observe(this) { updateTaxBreakdown() }
        viewModel.serviceFeeConfig.observe(this) { updateTaxBreakdown() }

        viewModel.isLoading.observe(this) { loading ->
            updatePayButtonState()
            if (loading) {
                binding.loadingOverlay.visibility = View.VISIBLE
            } else if (viewModel.saleResult.value !is SaleResult.Success) {
                binding.loadingOverlay.visibility = View.GONE
            }
        }

        lifecycleScope.launch {
            viewModel.isPaymentBlocked.collect {
                updatePayButtonState()
            }
        }

        lifecycleScope.launch {
            viewModel.canResumeSameOperation.collect {
                updatePayButtonState()
            }
        }

        lifecycleScope.launch {
            viewModel.blockReason.collect {
                updatePayButtonState()
            }
        }

        viewModel.saleResult.observe(this) { result ->
            when (result) {
                is SaleResult.Success -> {
                    val items = viewModel.cartItems.value ?: emptyList()
                    val snapshot = viewModel.latestReceiptSnapshot.value
                    val printTotal = snapshot?.transactionAmount?.toDouble() ?: (viewModel.finalTotal.value ?: 0.0)
                    val printCurrency = snapshot?.transactionCurrency ?: com.plugpdv.pdv.utils.CurrencyManager.getInstance().selectedCurrency
                    val printMethod = snapshot?.paymentMethod ?: (result.response.status ?: "PIX")
                    val saleId = result.response.id ?: ""

                    if (saleId.startsWith("LOCAL-")) {
                        // Venda salva offline - Sincronização em background
                    }

                    // 1. Imprime cupom detalhado com QR Code por produto em background usando valores congelados
                    lifecycleScope.launch(Dispatchers.IO) {
                        PrinterHelper.printDirectSaleReceipt(
                            context = this@CheckoutActivity,
                            cartItems = items,
                            total = printTotal,
                            currency = printCurrency,
                            paymentMethod = printMethod,
                            operatorName = operatorName,
                            saleId = saleId
                        )

                        withContext(Dispatchers.Main) {
                            // Só remove o loading após toda a impressão terminar
                            binding.loadingOverlay.visibility = View.GONE

                            // 2. Exibe modal de Factura Eletrônica
                            FacturaElectronicaDialog(this@CheckoutActivity) { emitir ->
                                if (emitir) {
                                    // Imprime factura eletrônica mockada
                                    PrinterHelper.printMockFactura(
                                        context = this@CheckoutActivity,
                                        total = printTotal,
                                        currency = printCurrency,
                                        operatorName = operatorName
                                    )
                                }

                                // 3. Volta para DirectSaleActivity com carrinho limpo
                                val intent = Intent(this@CheckoutActivity, DirectSaleActivity::class.java).apply {
                                    putExtra("ACCESS_TOKEN", token)
                                    putExtra("CLEAR_CART", true)
                                    flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
                                }
                                startActivity(intent)
                                finish()
                            }.show()
                        }
                    }
                }
                is SaleResult.Error -> {
                    binding.loadingOverlay.visibility = View.GONE
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
                null -> {}
            }
        }
    }

    private fun updateTaxBreakdown() {
        binding.layoutTaxBreakdown.removeAllViews()
        val cm = CurrencyManager.getInstance()
        val base = viewModel.baseTotal.value ?: 0.0
        val currentCurrency = cm.selectedCurrency
        
        addBreakdownRow(getString(R.string.subtotal), cm.format(base))
        
        viewModel.activeTaxes.value?.filter { it.currency.equals(currentCurrency, ignoreCase = true) }?.forEach { tax ->
            val calculatedTax = base * (tax.percentage / 100.0)
            val label = "${tax.name} (${String.format("%.1f%%", tax.percentage)})"
            addBreakdownRow(label, cm.format(calculatedTax))
        }
        
        // Add Service Fee — mostra se allow_override=true (independente de fixed_enabled)
        val sfAmount = viewModel.serviceFeeAmount.value ?: 0.0
        val sfConfig = viewModel.serviceFeeConfig.value
        val canOverride = sfConfig?.allowOverride == true

        if (canOverride || sfAmount > 0) {
            val sfRow = addBreakdownRow("Taxa de Serviço", cm.format(sfAmount))
            if (canOverride) {
                sfRow.setOnClickListener { showServiceFeeOverrideDialog() }
                val tvLabel = sfRow.findViewById<TextView>(R.id.tvLabel)
                tvLabel.text = "Taxa de Serviço (Alterar)"
                tvLabel.setTextColor(resources.getColor(com.google.android.material.R.color.design_default_color_primary, theme))
            }
        }
    }

    private fun addBreakdownRow(label: String, value: String): View {
        val row = layoutInflater.inflate(R.layout.item_tax_row, binding.layoutTaxBreakdown, false)
        row.findViewById<TextView>(R.id.tvLabel).text = label
        row.findViewById<TextView>(R.id.tvValue).text = value
        binding.layoutTaxBreakdown.addView(row)
        return row
    }

    private fun showServiceFeeOverrideDialog() {
        // Simple implementation: show a BottomSheet to select fee kind and value
        ServiceFeeOverrideBottomSheet.newInstance(viewModel.baseTotal.value ?: 0.0) { kind, value ->
            viewModel.overrideServiceFee(kind, value)
        }.show(supportFragmentManager, "service_fee_override")
    }

    private fun resumePreparedPaymentFlow() {
        lifecycleScope.launch {
            try {
                val prepared = viewModel.getPreparedOperationForResume()
                if (prepared == null) {
                    Toast.makeText(this@CheckoutActivity, "Operação não encontrada para retomada", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                pendingDirectSaleOperationId = prepared.localId
                paymentProcessed = false

                val intent = Intent(this@CheckoutActivity, PaymentHandlerActivity::class.java).apply {
                    putExtra(PaymentHandlerActivity.EXTRA_REQUEST_ID, prepared.localId)
                    putExtra(PaymentHandlerActivity.EXTRA_IDEMPOTENCY_KEY, prepared.localId)
                    putExtra(PaymentHandlerActivity.EXTRA_AMOUNT, prepared.saleRequest.total.toPlainString())
                    putExtra(PaymentHandlerActivity.EXTRA_AMOUNT_BRL, (prepared.saleRequest.convertedTotal ?: prepared.saleRequest.total).toPlainString())
                    putExtra(PaymentHandlerActivity.EXTRA_CURRENCY, prepared.saleRequest.paymentCurrency ?: prepared.saleRequest.currency)
                    putExtra(PaymentHandlerActivity.EXTRA_AMOUNTS_JSON, prepared.amountsJson)
                    putExtra(PaymentHandlerActivity.EXTRA_ORDER_ID, prepared.localId)
                    putExtra(PaymentHandlerActivity.EXTRA_MERCHANT_ID, operatorId ?: "merchant123")
                    putExtra(PaymentHandlerActivity.EXTRA_DESCRIPTION, "Venda Direta - PDV")
                }
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("CheckoutActivity", "Erro ao retomar pagamento preparado: ${e.message}", e)
                Toast.makeText(this@CheckoutActivity, "Erro ao retomar pagamento: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startPaymentFlow() {
        val hasDurableMarker = com.plugpdv.pdv.utils.DirectPaymentReconciliationStore.isReconciliationRequired(this)
        if (hasDurableMarker) {
            Toast.makeText(this, "Pagamento aprovado requer conciliação. Contate o suporte.", Toast.LENGTH_LONG).show()
            return
        }

        if (viewModel.isPaymentBlocked.value) {
            if (viewModel.canResumeSameOperation.value) {
                resumePreparedPaymentFlow()
                return
            }
            val reason = viewModel.blockReason.value ?: "Pagamento bloqueado"
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            return
        }

        Log.d("CheckoutActivity", "startPaymentFlow - Token: ${token != null}, Session: ${sessionId != null}")
        if (sessionId == null) {
            Toast.makeText(this, R.string.cashier_closed_msg, Toast.LENGTH_LONG).show()
            return
        }

        val total = viewModel.finalTotal.value ?: 0.0
        val baseCurrency = CurrencyManager.getInstance().getBaseCurrency()

        PaymentMethodSelectorBottomSheet.newInstance(total, baseCurrency) { method, quote ->
            Log.d("CheckoutActivity", "Método selecionado: $method, Quote: $quote")
            when (method) {
                PaymentMethodSelectorBottomSheet.PaymentType.CASH -> {
                    viewModel.finishCashSale(quote, sessionId ?: "", operatorId, operatorName)
                }
                PaymentMethodSelectorBottomSheet.PaymentType.PLUG_PAY -> {
                    lifecycleScope.launch {
                        try {
                            paymentProcessed = false
                            val prepared = viewModel.prepareDirectSaleOperation(quote, "CREDITO", sessionId ?: "", operatorId, operatorName)
                            pendingDirectSaleOperationId = prepared.localId

                            val amountsJsonStr = com.plugpdv.pdv.utils.PaymentHelper.generateAmountsJsonExact(
                                baseAmount = quote.baseAmount,
                                baseCurrency = quote.baseCurrency,
                                transactionCurrency = quote.transactionCurrency,
                                transactionAmount = quote.transactionAmount,
                                snapshot = quote.snapshot,
                                activeTaxes = emptyList()
                            )

                            val intent = Intent(this@CheckoutActivity, PaymentHandlerActivity::class.java).apply {
                                putExtra(PaymentHandlerActivity.EXTRA_REQUEST_ID, prepared.localId)
                                putExtra(PaymentHandlerActivity.EXTRA_IDEMPOTENCY_KEY, prepared.localId)
                                putExtra(PaymentHandlerActivity.EXTRA_AMOUNT, quote.transactionAmount.toPlainString())
                                putExtra(PaymentHandlerActivity.EXTRA_AMOUNT_BRL, quote.baseAmount.toPlainString())
                                putExtra(PaymentHandlerActivity.EXTRA_CURRENCY, quote.transactionCurrency)
                                putExtra(PaymentHandlerActivity.EXTRA_AMOUNTS_JSON, amountsJsonStr)
                                putExtra(PaymentHandlerActivity.EXTRA_ORDER_ID, prepared.localId)
                                putExtra(PaymentHandlerActivity.EXTRA_MERCHANT_ID, operatorId ?: "merchant123")
                                putExtra(PaymentHandlerActivity.EXTRA_DESCRIPTION, "Venda Direta - PDV")
                            }
                            startActivity(intent)
                        } catch (e: Exception) {
                            Log.e("CheckoutActivity", "Erro ao iniciar pagamento direto: ${e.message}", e)
                            Toast.makeText(this@CheckoutActivity, "Erro ao iniciar pagamento: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }.show(supportFragmentManager, "payment_selector")
    }

    private fun showItemsDetail() {
        val sheet = CheckoutItemsBottomSheet.newInstance(
            ArrayList(viewModel.cartItems.value ?: emptyList()),
            ArrayList(viewModel.activeTaxes.value ?: emptyList())
        ) { updatedItems ->
            viewModel.init(updatedItems)
        }
        sheet.show(supportFragmentManager, "checkout_items")
    }

    /**
     * Converte o valor de `method` recebido do PixPlug para o método interno da API.
     * Ref: deeplink_uri_reference.md seção 4
     */
    private fun mapToApiMethod(method: String): String {
        return when (method.uppercase()) {
            "PIX"                 -> "PIX"
            "CREDIT_INSTALLMENTS" -> "CREDITO"
            "CREDIT"              -> "CREDITO"
            "DEBIT"               -> "DEBITO"
            "QR_PYG"              -> "QR_PYG"
            "QR_ARS"              -> "QR_ARS"
            "DINHEIRO", "CASH"    -> "DINHEIRO"
            else                  -> "PIX"
        }
    }
}
