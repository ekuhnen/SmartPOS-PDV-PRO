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

        cartItems?.let { viewModel.init(it, token) }

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

    override fun onResume() {
        super.onResume()
        checkPendingPaymentResult()
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
    }

    /**
     * Verifica se o PaymentResultStore tem um resultado de pagamento aprovado pendente.
     * Chamado em onResume e onNewIntent para garantir que o resultado seja processado
     * independentemente de como o CheckoutActivity foi retomado.
     */
    private fun checkPendingPaymentResult() {
        if (paymentProcessed) return
        val result = PaymentResultStore.consume() ?: return

        if (result.status.equals("APPROVED", ignoreCase = true)) {
            Log.d("CheckoutActivity", "Pagamento aprovado recebido via PaymentResultStore. method=${result.method}")
            paymentProcessed = true
            val method = mapToApiMethod(result.method ?: "PIX")
            token?.let {
                viewModel.finishSale(it, method, sessionId ?: "", operatorId, operatorName)
            } ?: Log.e("CheckoutActivity", "Token nulo ao processar resultado de pagamento!")
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
            binding.btnPayLink.isEnabled = !loading
            if (loading) {
                binding.loadingOverlay.visibility = View.VISIBLE
            } else if (viewModel.saleResult.value !is SaleResult.Success) {
                binding.loadingOverlay.visibility = View.GONE
            }
        }

        viewModel.saleResult.observe(this) { result ->
            when (result) {
                is SaleResult.Success -> {
                    val items = viewModel.cartItems.value ?: emptyList()
                    val total = viewModel.finalTotal.value ?: 0.0
                    val currency = com.plugpdv.pdv.utils.CurrencyManager.getInstance().selectedCurrency
                    val saleId = result.response.id ?: ""

                    if (saleId.startsWith("LOCAL-")) {
                        // Venda salva offline - Sincronização em background
                    }

                    // 1. Imprime cupom detalhado com QR Code por produto em background
                    lifecycleScope.launch(Dispatchers.IO) {
                        PrinterHelper.printDirectSaleReceipt(
                            context = this@CheckoutActivity,
                            cartItems = items,
                            total = total,
                            currency = currency,
                            paymentMethod = result.response.status ?: "PIX",
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
                                        total = total,
                                        currency = currency,
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

    private fun startPaymentFlow() {
        Log.d("CheckoutActivity", "startPaymentFlow - Token: ${token != null}, Session: ${sessionId != null}")
        if (sessionId == null) {
            Toast.makeText(this, R.string.cashier_closed_msg, Toast.LENGTH_LONG).show()
            return
        }

        val total = viewModel.finalTotal.value ?: 0.0
        
        PaymentMethodSelectorBottomSheet.newInstance(total) { method, txAmount, txCurrency, baseAmount ->
            Log.d("CheckoutActivity", "Método selecionado: $method, TxAmount: $txAmount $txCurrency, BaseAmount: $baseAmount")
            when (method) {
                PaymentMethodSelectorBottomSheet.PaymentType.CASH -> {
                    // Finaliza direto em dinheiro com o valor selecionado
                    token?.let {
                        viewModel.finishSale(it, "DINHEIRO", sessionId ?: "", operatorId, operatorName, baseAmount)
                    } ?: Log.e("CheckoutActivity", "Impossível finalizar: Token is NULL")
                }
                PaymentMethodSelectorBottomSheet.PaymentType.PLUG_PAY -> {
                    paymentProcessed = false
                    val cm = CurrencyManager.getInstance()
                    val activeTaxes = viewModel.activeTaxes.value ?: emptyList()
                    val amountsJsonStr = com.plugpdv.pdv.utils.PaymentHelper.generateAmountsJson(baseAmount, txCurrency, activeTaxes, cm)
                    
                    val intent = Intent(this, PaymentHandlerActivity::class.java).apply {
                        putExtra(PaymentHandlerActivity.EXTRA_AMOUNT, txAmount.toString())
                        putExtra(PaymentHandlerActivity.EXTRA_AMOUNT_BRL, baseAmount.toString())
                        putExtra(PaymentHandlerActivity.EXTRA_AMOUNTS_JSON, amountsJsonStr)
                        putExtra(PaymentHandlerActivity.EXTRA_ORDER_ID, "direct_${System.currentTimeMillis()}")
                        putExtra(PaymentHandlerActivity.EXTRA_MERCHANT_ID, operatorId ?: "merchant123")
                        putExtra(PaymentHandlerActivity.EXTRA_DESCRIPTION, "Venda Direta - PDV")
                    }
                    startActivity(intent)
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
