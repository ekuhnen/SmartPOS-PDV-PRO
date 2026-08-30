package com.plugpdv.pdv.ui.sale

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.plugpdv.pdv.R
import com.plugpdv.pdv.databinding.ItemCheckoutSplitBinding
import com.plugpdv.pdv.databinding.ItemTaxRowBinding
import com.plugpdv.pdv.databinding.LayoutTableCheckoutBinding
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.models.TableItem
import com.plugpdv.pdv.models.TableItemPayment
import com.plugpdv.pdv.utils.*
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.math.BigDecimal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@AndroidEntryPoint
class TableCheckoutBottomSheet : BottomSheetDialogFragment() {
    private var token: String? = null
    private var binding: LayoutTableCheckoutBinding? = null
    
    private val viewModel: CheckoutViewModel by viewModels()

    private var pendingCheckoutOperationId: String? = null

    private val paymentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val status = result.data?.getStringExtra("status")
            val methodStr = result.data?.getStringExtra("method") ?: "PIX"
            val paymentId = result.data?.getStringExtra("payment_id")
            val requestId = result.data?.getStringExtra("request_id") ?: pendingCheckoutOperationId
            if ("APPROVED" == status && !requestId.isNullOrEmpty()) {
                val method = PaymentMethod.fromString(methodStr)
                viewModel.finalizeApprovedCheckout(requestId, paymentId, method)
            } else {
                Toast.makeText(context, "Pagamento não aprovado: $status", Toast.LENGTH_LONG).show()
            }
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            val message = result.data?.getStringExtra("message") ?: "Cancelado"
            Toast.makeText(context, "Pagamento Cancelado/Erro: $message", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tableId = arguments?.getString("TABLE_ID")
        val tableNumber = arguments?.getInt("TABLE_NUMBER") ?: 0
        val sectorId = arguments?.getString("SECTOR_ID")
        token = arguments?.getString("TOKEN")
        
        if (token == null || (tableId.isNullOrEmpty() && tableNumber <= 0)) {
            dismiss()
            return
        }

        val prefs = requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        viewModel.init(
            tableId = tableId,
            tableNumber = tableNumber,
            sectorId = sectorId,
            token = token!!,
            sessionId = prefs.getString(Constants.SESSION_ID, null),
            opId = prefs.getString(Constants.OPERATOR_ID, null),
            opName = prefs.getString(Constants.OPERATOR_NAME, null)
        )
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        binding = LayoutTableCheckoutBinding.inflate(inflater, container, false)
        return binding?.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupUI()
        observeViewModel()
    }

    override fun onResume() {
        super.onResume()
        checkPendingPaymentResult()
    }

    private fun checkPendingPaymentResult() {
        val result = PaymentResultStore.consume() ?: return
        if (result.status.equals("APPROVED", ignoreCase = true)) {
            Log.d("TableCheckoutBottomSheet", "Pagamento aprovado recebido via PaymentResultStore. method=${result.method}")
            val method = PaymentMethod.fromString(result.method)
            val key = pendingCheckoutOperationId
            if (!key.isNullOrEmpty()) {
                viewModel.finalizeApprovedCheckout(key, result.paymentId, method)
            }
        }
    }

    private fun setupUI() {
        val b = binding ?: return
        
        b.fabCurrency.setOnClickListener {
            (activity as? com.plugpdv.pdv.ui.BaseActivity)?.showCurrencySelector {
                refreshUI()
            }
        }

        b.toggleGroupMode.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.btnModeFull -> 0
                R.id.btnModeSplitPeople -> 1
                R.id.btnModeSplitItems -> 2
                else -> 0
            }
            viewModel.setSplitMode(mode)
        }

        b.etPeopleCount.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val count = s.toString().toIntOrNull() ?: 1
                viewModel.updatePeopleSplit(count)
            }
        })

        b.btnPayLink.setOnClickListener { finalizePayment() }
        b.btnPrinter.setOnClickListener { printTableReceipt() }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: CheckoutUiState) {
        val b = binding ?: return
        val cm = CurrencyManager.getInstance()
        val digits = state.baseMinorUnitDigits
        val baseCurrency = state.baseCurrency ?: cm.selectedCurrency

        if (digits == null || state.baseCurrency.isNullOrBlank()) {
            b.tvComandaTotal.text = "--"
            b.tvTotalPaid.text = "--"
            b.tvPendingBalance.text = "--"
            b.tvTotalToPay.text = "--"
        } else {
            val totalDecimal = state.totalBaseMinor?.let {
                ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits)
            } ?: BigDecimal.ZERO

            val paidDecimal = state.paidBaseMinor?.let {
                ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits)
            } ?: BigDecimal.ZERO

            val balanceDecimal = state.balanceBaseMinor?.let {
                ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits)
            } ?: BigDecimal.ZERO

            fun formatMoney(amount: BigDecimal): String {
                val selectedCurrency = cm.selectedCurrency
                if (selectedCurrency.equals(baseCurrency, ignoreCase = true)) {
                    return cm.formatExplicit(amount.toDouble(), baseCurrency)
                }
                val quote = cm.quoteTransactionAmount(amount, selectedCurrency, baseCurrency).getOrNull()
                return if (quote != null) {
                    cm.formatExplicit(quote.transactionAmount.toDouble(), selectedCurrency)
                } else {
                    cm.formatExplicit(amount.toDouble(), baseCurrency)
                }
            }

            b.tvComandaTotal.text = formatMoney(totalDecimal)
            b.tvTotalPaid.text = formatMoney(paidDecimal)
            b.tvPendingBalance.text = formatMoney(balanceDecimal)
            b.tvTotalToPay.text = cm.format(state.finalToPay)
        }

        if (state.moneyAuthorityState == MoneyAuthorityState.LOAD_ERROR) {
            b.btnPayLink.isEnabled = true
            b.btnPayLink.text = "Tentar novamente"
            b.btnPayLink.setOnClickListener { viewModel.fetchComandaPayments() }
        } else {
            b.btnPayLink.setOnClickListener { finalizePayment() }
            b.btnPayLink.isEnabled = (state.moneyAuthorityState == MoneyAuthorityState.READY_REMOTE) && !state.isLoading && !state.isPayButtonBlocked && !state.requiresReconciliation && state.baseMinorUnitDigits != null
            if (state.baseMinorUnitDigits == null && state.moneyAuthorityState != MoneyAuthorityState.LOAD_ERROR) {
                b.btnPayLink.text = "Dados financeiros indisponíveis"
            } else if (state.moneyAuthorityState == MoneyAuthorityState.LOADING) {
                b.btnPayLink.text = "Carregando comanda..."
            } else if (state.requiresReconciliation) {
                b.btnPayLink.text = "Pagamento aprovado requer conciliação"
            } else if (state.isPayButtonBlocked && !state.blockReason.isNullOrEmpty()) {
                b.btnPayLink.text = state.blockReason
            } else {
                b.btnPayLink.text = "Cobrar"
            }
        }

        populatePaymentsHistory(state.paymentsHistory)

        if (state.paymentSuccess) {
            onUpdateNotify()
            
            // Print the transaction receipt automatically
            state.lastPaymentMethod?.let { method ->
                printPaymentReceipt(method, state.lastPaymentAmount)
            }
            
            val isFullyPaid = state.balanceBaseMinor != null && state.balanceBaseMinor <= 0L

            if (isFullyPaid) {
                viewModel.acknowledgePaymentSuccess()
                val totalFactura = state.fullTableTotalPaid
                
                FacturaElectronicaDialog(requireContext()) { emitir ->
                    if (emitir) {
                        val prefs = requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                        val operatorName = prefs.getString(Constants.OPERATOR_NAME, "Operador")
                        PrinterHelper.printMockFactura(
                            context = requireContext(),
                            total = totalFactura,
                            currency = cm.selectedCurrency,
                            operatorName = operatorName
                        )
                    }
                    dismiss()
                    activity?.finish()
                }.show()
                
            } else {
                Toast.makeText(context, getString(R.string.partial_payment_approved), Toast.LENGTH_SHORT).show()
                viewModel.acknowledgePaymentSuccess()
            }
        }

        state.error?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
        }

        // Layout visibility based on mode
        b.layoutPeople.visibility = if (state.splitMode == 1) View.VISIBLE else View.GONE
        b.layoutItems.visibility = if (state.splitMode == 2) View.VISIBLE else View.GONE
        
        if (state.splitMode == 1) {
            b.tvPerPersonValue.text = "Valor por pessoa: ${cm.format(state.currentToPay)}"
        }

        if (state.splitMode == 2) {
            setupItemsAdapter()
        }

        populateTaxBreakdown(state)
    }

    private fun populatePaymentsHistory(payments: List<com.plugpdv.pdv.models.ComandaPaymentDto>) {
        val b = binding ?: return
        b.layoutPaymentsList.removeAllViews()
        if (payments.isEmpty()) {
            b.layoutPaymentsContainer.visibility = View.GONE
            return
        }

        b.layoutPaymentsContainer.visibility = View.VISIBLE
        val cm = CurrencyManager.getInstance()
        payments.forEach { payment ->
            val rowBinding = ItemTaxRowBinding.inflate(layoutInflater, b.layoutPaymentsList, true)
            val formaLabel = when (payment.forma.uppercase()) {
                "DINHEIRO", "CASH" -> getString(R.string.cash)
                "PIX", "PIX_TRANSFERENCIA" -> getString(R.string.pix)
                "CREDITO", "CREDIT" -> getString(R.string.credit)
                "DEBITO", "DEBIT" -> getString(R.string.debit)
                "CARTAO", "CARD" -> getString(R.string.credit)
                else -> payment.forma
            }
            rowBinding.tvLabel.text = formaLabel
            rowBinding.tvValue.text = cm.formatExplicit(payment.valor, payment.moeda)
            context?.let { ctx ->
                rowBinding.tvValue.setTextColor(ctx.getColor(R.color.success))
            }
        }
    }

    private fun setupItemsAdapter() {
        val b = binding ?: return
        if (b.rvSelectItems.adapter == null) {
            b.rvSelectItems.layoutManager = LinearLayoutManager(context)
            b.rvSelectItems.adapter = ItemsAdapter(viewModel.itemsToPay) { pos, selected ->
                viewModel.onItemSelected(pos, selected)
            }
        } else {
            b.rvSelectItems.adapter?.notifyDataSetChanged()
        }
    }

    private fun populateTaxBreakdown(state: CheckoutUiState) {
        val b = binding ?: return
        b.layoutTaxBreakdown.removeAllViews()
        val cm = CurrencyManager.getInstance()
        
        val baseToPay = state.currentToPay.coerceAtLeast(0.0)
        addBreakdownRow(getString(R.string.subtotal), cm.format(baseToPay))
        
        val currentCurrency = cm.selectedCurrency
        state.activeTaxes.filter { it.currency.equals(currentCurrency, ignoreCase = true) }.forEach { tax ->
            val calculatedTax = baseToPay * (tax.percentage / 100.0)
            val label = "${tax.name} (${String.format("%.1f%%", tax.percentage)})"
            addBreakdownRow(label, cm.format(calculatedTax))
        }

        // Add Service Fee
        val sfAmount = state.serviceFeeAmount
        val sfConfig = state.serviceFeeConfig
        val canOverride = sfConfig?.allowOverride == true
        if (canOverride || sfAmount > 0) {
            val sfRowBinding = addBreakdownRow("Taxa de Serviço", cm.format(sfAmount))
            if (canOverride) {
                sfRowBinding.root.setOnClickListener { showServiceFeeOverrideDialog() }
                sfRowBinding.tvLabel.text = "Taxa de Serviço (Alterar)"
                sfRowBinding.tvLabel.setTextColor(requireContext().getColor(com.google.android.material.R.color.design_default_color_primary))
            }
        }
    }

    private fun addBreakdownRow(label: String, value: String): ItemTaxRowBinding {
        val b = binding ?: return ItemTaxRowBinding.inflate(layoutInflater)
        val rowBinding = ItemTaxRowBinding.inflate(layoutInflater, b.layoutTaxBreakdown, true)
        rowBinding.tvLabel.text = label
        rowBinding.tvValue.text = value
        return rowBinding
    }

    private fun showServiceFeeOverrideDialog() {
        val baseAmount = viewModel.uiState.value.currentToPay
        ServiceFeeOverrideBottomSheet.newInstance(baseAmount) { kind, value ->
            viewModel.overrideServiceFee(kind, value)
        }.show(childFragmentManager, "service_fee_override")
    }

    private fun finalizePayment() {
        val state = viewModel.uiState.value
        if (state.moneyAuthorityState != MoneyAuthorityState.READY_REMOTE || !viewModel.moneyAuthorityLoaded || viewModel.comandaBaseCurrency.isNullOrBlank() || state.isPayButtonBlocked || state.requiresReconciliation) {
            Toast.makeText(context, state.blockReason ?: "Dados financeiros da comanda não carregados", Toast.LENGTH_SHORT).show()
            return
        }
        if (state.currentToPay <= 0) {
            Toast.makeText(context, "Valor inválido", Toast.LENGTH_SHORT).show()
            return
        }

        PaymentMethodSelectorBottomSheet.newInstance(state.finalToPay, viewModel.comandaBaseCurrency) { method, quote ->
            Log.d("TableCheckoutBottomSheet", "Método selecionado: $method, Quote: $quote")
            when (method) {
                PaymentMethodSelectorBottomSheet.PaymentType.CASH -> {
                    viewModel.finalizePayment(PaymentMethod.CASH, suppliedQuote = quote)
                }
                PaymentMethodSelectorBottomSheet.PaymentType.PLUG_PAY -> {
                    lifecycleScope.launch {
                        try {
                            val prepared = viewModel.prepareCheckoutOperation(PaymentMethod.CREDIT, suppliedQuote = quote)
                            pendingCheckoutOperationId = prepared.operationKey

                            val prefs = requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                            val operatorId = prefs.getString(Constants.OPERATOR_ID, null)

                            val amountsJsonStr = PaymentHelper.generateAmountsJsonExact(
                                baseAmount = prepared.request.valorBase ?: prepared.request.valor,
                                baseCurrency = prepared.request.baseCurrency ?: quote.baseCurrency,
                                transactionCurrency = quote.transactionCurrency,
                                transactionAmount = prepared.request.valor,
                                snapshot = prepared.request.exchangeRatesSnapshot ?: quote.snapshot,
                                activeTaxes = emptyList()
                            )

                            val intent = Intent(context, PaymentHandlerActivity::class.java).apply {
                                putExtra(PaymentHandlerActivity.EXTRA_REQUEST_ID, prepared.operationKey)
                                putExtra(PaymentHandlerActivity.EXTRA_IDEMPOTENCY_KEY, prepared.operationKey)
                                putExtra(PaymentHandlerActivity.EXTRA_AMOUNT, prepared.request.valor.toPlainString())
                                putExtra(PaymentHandlerActivity.EXTRA_AMOUNT_BRL, (prepared.request.valorBase ?: prepared.request.valor).toPlainString())
                                putExtra(PaymentHandlerActivity.EXTRA_CURRENCY, quote.transactionCurrency)
                                putExtra(PaymentHandlerActivity.EXTRA_AMOUNTS_JSON, amountsJsonStr)
                                putExtra(PaymentHandlerActivity.EXTRA_ORDER_ID, prepared.request.comandaId)
                                putExtra(PaymentHandlerActivity.EXTRA_TABLE_ID, prepared.request.mesaId)
                                putExtra(PaymentHandlerActivity.EXTRA_MERCHANT_ID, operatorId ?: "merchant123")
                            }
                            paymentLauncher.launch(intent)
                        } catch (e: Exception) {
                            Log.e("TableCheckoutBottomSheet", "Erro ao preparar checkout: ${e.message}")
                            Toast.makeText(context, "Erro ao iniciar pagamento: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }.show(childFragmentManager, "payment_selector")
    }

    private fun printTableReceipt() {
        val ctx = context?.let { LanguageManager.updateResources(it, LanguageManager.getLanguage(it)) } ?: return
        val state = viewModel.uiState.value
        val sb = StringBuilder()
        val cm = CurrencyManager.getInstance()

        sb.append(ctx.getString(R.string.print_table_label)).append("\n")
        sb.append("--------------------------------\n")

        val digits = state.baseMinorUnitDigits ?: return
        val baseCurrency = state.baseCurrency ?: cm.selectedCurrency
        val totalDecimal = state.totalBaseMinor?.let {
            ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits)
        } ?: BigDecimal.ZERO
        val balanceDecimal = state.balanceBaseMinor?.let {
            ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits)
        } ?: BigDecimal.ZERO

        sb.append("Total: ").append(cm.formatExplicit(totalDecimal.toDouble(), baseCurrency)).append("\n")
        sb.append("Saldo: ").append(cm.formatExplicit(balanceDecimal.toDouble(), baseCurrency)).append("\n")
        sb.append("--------------------------------\n")

        PrinterHelper.printReceipt(requireContext(), sb.toString())
    }

    private fun printPaymentReceipt(method: String, amount: Double) {
        val ctx = context ?: return
        val cm = CurrencyManager.getInstance()
        val currentCurrency = cm.selectedCurrency
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault())
        val dateStr = sdf.format(Date())
        
        val sb = StringBuilder()
        sb.append("================================\n")
        sb.append("      COMPROVANTE DE PAGAMENTO  \n")
        sb.append("================================\n")
        sb.append("Data/Hora: $dateStr\n")
        sb.append("Forma: $method\n")
        sb.append("Valor Pago: ${cm.formatExplicit(amount, currentCurrency)}\n")
        sb.append("================================\n\n\n")

        PrinterHelper.printReceipt(ctx, sb.toString())
    }

    private fun refreshUI() {
        viewModel.refreshCalculations()
    }

    private fun onUpdateNotify() {
        (activity as? TableOrderActivity)?.updateUI()
        (activity as? CommandOrderActivity)?.updateUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        @JvmStatic
        fun newInstance(tableNumber: Int, token: String): TableCheckoutBottomSheet {
            return newInstance(null, tableNumber, token)
        }

        @JvmStatic
        fun newInstance(tableId: String?, tableNumber: Int, token: String): TableCheckoutBottomSheet {
            return TableCheckoutBottomSheet().apply {
                arguments = Bundle().apply {
                    putString("TABLE_ID", tableId)
                    putInt("TABLE_NUMBER", tableNumber)
                    putString("TOKEN", token)
                }
            }
        }
    }

    private inner class ItemsAdapter(
        private val items: List<TableItemPayment>,
        private val onSelect: (Int, Boolean) -> Unit
    ) : RecyclerView.Adapter<ItemsAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val b = ItemCheckoutSplitBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return ViewHolder(b)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val tip = items[position]
            holder.binding.cbItemSelected.isChecked = tip.selected
            holder.binding.tvItemName.text = "${tip.item.product.name ?: "Sem Nome"} (x${tip.selectedQuantity})"
            val price = tip.item.product.selling_price
            holder.binding.tvItemValue.text = if (price != null) CurrencyManager.getInstance().format(price) else "UNKNOWN"
            holder.binding.tvItemSubtotal.text = if (price != null) CurrencyManager.getInstance().format(price * tip.selectedQuantity) else "UNKNOWN"
            
            holder.itemView.setOnClickListener {
                tip.selected = !tip.selected
                notifyItemChanged(position)
                onSelect(position, tip.selected)
            }
            holder.binding.cbItemSelected.setOnClickListener {
                onSelect(position, holder.binding.cbItemSelected.isChecked)
            }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(val binding: ItemCheckoutSplitBinding) : RecyclerView.ViewHolder(binding.root)
    }
}
