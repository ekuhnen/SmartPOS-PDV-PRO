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
    private var tableNumber: Int = 0
    private var binding: LayoutTableCheckoutBinding? = null
    
    private val viewModel: CheckoutViewModel by viewModels()

    private var pendingCheckoutOperationId: String? = null
    private var latestState: CheckoutUiState? = null

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
                Toast.makeText(context, getString(R.string.payment_not_approved, status), Toast.LENGTH_LONG).show()
            }
        } else if (result.resultCode == Activity.RESULT_CANCELED) {
            val message = result.data?.getStringExtra("message") ?: "Cancelado"
            Toast.makeText(context, getString(R.string.payment_cancelled_or_error, message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingCheckoutOperationId = savedInstanceState?.getString("PENDING_CHECKOUT_OPERATION_ID")
        val tableId = arguments?.getString("TABLE_ID")
        tableNumber = arguments?.getInt("TABLE_NUMBER") ?: 0
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
            val key = CheckoutOperationCorrelation.resolve(result.requestId, pendingCheckoutOperationId)
            if (!key.isNullOrEmpty()) {
                viewModel.finalizeApprovedCheckout(key, result.paymentId, method)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("PENDING_CHECKOUT_OPERATION_ID", pendingCheckoutOperationId)
        super.onSaveInstanceState(outState)
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
        b.btnCalculationDetails.setOnClickListener { latestState?.let { showCalculationDetails(it) } }
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
        latestState = state
        val cm = CurrencyManager.getInstance()
        val digits = state.baseMinorUnitDigits
        val baseCurrency = state.baseCurrency ?: cm.selectedCurrency

        val showSyncStatus = state.isPendingSync || state.isLoading || state.requiresReconciliation
        b.paymentSyncStatusRow.visibility = if (showSyncStatus) View.VISIBLE else View.GONE
        b.tvPaymentSyncStatus.text = when {
            state.requiresReconciliation -> getString(R.string.payment_requires_reconciliation)
            state.isPendingSync && state.isLoading -> getString(R.string.payment_syncing)
            state.isPendingSync -> getString(R.string.payment_approved_pending_sync)
            else -> ""
        }

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
                val quote = cm.quoteBaseAmount(amount, baseCurrency, selectedCurrency).getOrNull()
                return if (quote != null) {
                    cm.formatExplicit(quote.transactionAmount.toDouble(), selectedCurrency)
                } else {
                    cm.formatExplicit(amount.toDouble(), baseCurrency)
                }
            }

            b.tvComandaTotal.text = formatMoney(totalDecimal)
            b.tvTotalPaid.text = formatMoney(paidDecimal)
            b.tvPendingBalance.text = formatMoney(balanceDecimal)
            b.tvTotalToPay.text = formatBaseAmount(state.finalToPay, baseCurrency)
        }

        if (state.moneyAuthorityState == MoneyAuthorityState.LOAD_ERROR) {
            b.btnPayLink.isEnabled = true
            b.btnPayLink.setText(R.string.retry)
            b.btnPayLink.setOnClickListener { viewModel.fetchComandaPayments() }
        } else {
            b.btnPayLink.setOnClickListener { finalizePayment() }
            val itemSelectionMissing = state.splitMode == 2 && state.currentToPay <= 0.0
            b.btnPayLink.isEnabled = (state.moneyAuthorityState == MoneyAuthorityState.READY_REMOTE) && !state.isLoading && !state.isPayButtonBlocked && !state.requiresReconciliation && state.baseMinorUnitDigits != null && !itemSelectionMissing
            if (state.baseMinorUnitDigits == null && state.moneyAuthorityState != MoneyAuthorityState.LOAD_ERROR) {
                b.btnPayLink.setText(R.string.financial_data_unavailable)
            } else if (state.moneyAuthorityState == MoneyAuthorityState.LOADING) {
                b.btnPayLink.setText(R.string.loading_comanda)
            } else if (state.requiresReconciliation) {
                b.btnPayLink.setText(R.string.payment_requires_reconciliation_short)
            } else if (itemSelectionMissing) {
                b.btnPayLink.setText(R.string.select_item_for_payment)
            } else if (state.isPayButtonBlocked && !state.blockReason.isNullOrEmpty()) {
                b.btnPayLink.setText(R.string.sync_action)
            } else {
                b.btnPayLink.setText(R.string.charge)
            }
        }

        populatePaymentsHistory(state.paymentsHistory)

        if (state.paymentSuccess) {
            onUpdateNotify()
            
            // Print the transaction receipt automatically
            state.lastPaymentMethod?.let { method ->
                state.lastPaymentCurrency?.let { currency ->
                    printPaymentReceipt(method, state.lastPaymentAmount, currency, viewModel.currentReceiptAllocations())
                }
            }
            
            // A closed commit is authoritative even when the pre-commit balance
            // snapshot has not refreshed yet.
            val isFullyPaid = state.isComandaClosed ||
                (state.balanceBaseMinor != null && state.balanceBaseMinor <= 0L)

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
            b.tvPerPersonValue.text = getString(R.string.value_per_person, formatBaseAmount(state.currentToPay, baseCurrency))
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
            b.rvSelectItems.adapter = PayByItemsAdapter(
                viewModel.itemsToPay,
                onSelect = { pos, selected -> viewModel.onItemSelected(pos, selected) },
                onQuantityChanged = { pos, delta -> viewModel.updateItemSelectedQuantity(pos, delta) }
            )
        } else {
            b.rvSelectItems.adapter?.notifyDataSetChanged()
        }
    }

    private fun populateTaxBreakdown(state: CheckoutUiState) {
        val b = binding ?: return
        b.layoutTaxBreakdown.removeAllViews()
        val cm = CurrencyManager.getInstance()
        
        val currency = state.baseCurrency ?: cm.getBaseCurrency()
        addBreakdownRow(getString(R.string.subtotal), formatBaseAmount(state.authoritativeSubtotal ?: state.currentToPay, currency))
        state.authoritativeTaxAmount?.let { amount ->
            val snapshot = state.authoritativeTaxSnapshot
            val taxName = snapshot?.name ?: getString(R.string.tax)
            val rate = snapshot?.rate
            val label = if (rate != null) "$taxName (${rate.toString().removeSuffix(".0")}%)" else taxName
            addBreakdownRow(label, formatBaseAmount(amount, currency))
        }

        // Add Service Fee
        val sfAmount = state.authoritativeServiceFee ?: state.serviceFeeAmount
        val sfConfig = state.serviceFeeConfig
        val canOverride = sfConfig?.allowOverride == true
        if (canOverride || sfAmount > 0) {
            val sfRowBinding = addBreakdownRow(getString(R.string.service_fee_label_ui), formatBaseAmount(sfAmount, state.baseCurrency ?: cm.getBaseCurrency()))
            if (canOverride) {
                sfRowBinding.root.setOnClickListener { showServiceFeeOverrideDialog() }
                sfRowBinding.tvLabel.setText(R.string.service_fee_change)
                sfRowBinding.tvLabel.setTextColor(requireContext().getColor(com.google.android.material.R.color.design_default_color_primary))
            }
        }
    }

    private fun applicableTaxAmounts(state: CheckoutUiState): List<Pair<com.plugpdv.pdv.database.TaxEntity, Double>> = emptyList()

    private fun showCalculationDetails(state: CheckoutUiState) {
        val ctx = context ?: return
        val cm = CurrencyManager.getInstance()
        val baseCurrency = state.baseCurrency ?: cm.getBaseCurrency()
        val selected = cm.selectedCurrency
        val baseSubtotal = cm.formatExplicit(state.currentToPay, baseCurrency)
        val lines = StringBuilder()
            .append(getString(R.string.base_subtotal)).append(": ").append(baseSubtotal).append("\n")
            .append(getString(R.string.transaction_currency)).append(": ").append(selected).append("\n")
        if (baseCurrency.equals(selected, ignoreCase = true)) {
            lines.append(getString(R.string.no_exchange_applied)).append("\n")
        } else {
            val quote = cm.quoteBaseAmount(BigDecimal.valueOf(state.currentToPay), baseCurrency, selected).getOrNull()
            if (quote != null) {
                lines.append(getString(R.string.applied_rate)).append(": 1 ").append(baseCurrency)
                    .append(" = ").append(cm.formatExplicit(quote.fxRate.toDouble(), selected)).append("\n")
                lines.append(getString(R.string.converted_subtotal)).append(": ")
                    .append(cm.formatExplicit(quote.transactionAmount.toDouble(), selected)).append("\n")
            }
        }
        lines.append(getString(R.string.transaction_taxes)).append(":\n")
        val authoritativeTax = state.authoritativeTaxAmount
        if (authoritativeTax == null) {
            lines.append(getString(R.string.tax_none)).append("\n")
        } else {
            lines.append(getString(R.string.tax)).append(": ")
                .append(formatBaseAmount(authoritativeTax, baseCurrency)).append("\n")
        }
        lines.append(getString(R.string.total_to_pay_label)).append(": ")
            .append(formatBaseAmount(state.finalToPay, baseCurrency))
        androidx.appcompat.app.AlertDialog.Builder(ctx)
            .setTitle(getString(R.string.view_calculation))
            .setMessage(lines.toString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun formatBaseAmount(amount: Double, baseCurrency: String): String {
        val cm = CurrencyManager.getInstance()
        val selected = cm.selectedCurrency
        if (baseCurrency.equals(selected, ignoreCase = true)) {
            return cm.formatExplicit(amount, baseCurrency)
        }
        val quote = cm.quoteBaseAmount(BigDecimal.valueOf(amount), baseCurrency, selected).getOrNull()
        return if (quote != null) {
            cm.formatExplicit(quote.transactionAmount.toDouble(), quote.transactionCurrency)
        } else {
            cm.formatExplicit(amount, baseCurrency)
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
            Toast.makeText(context, state.blockReason ?: getString(R.string.comanda_financial_data_not_loaded), Toast.LENGTH_SHORT).show()
            return
        }
        if (state.currentToPay <= 0) {
            Toast.makeText(context, if (state.splitMode == 2) R.string.select_item_for_payment else R.string.invalid_value, Toast.LENGTH_SHORT).show()
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
                            Toast.makeText(context, getString(R.string.start_payment_error, e.message.orEmpty()), Toast.LENGTH_LONG).show()
                        }
                    }
                }
            }
        }.show(childFragmentManager, "payment_selector")
    }

    private fun printTableReceipt() {
        val ctx = context?.let { LanguageManager.updateResources(it, LanguageManager.getLanguage(it)) } ?: return
        val state = viewModel.uiState.value
        val cm = CurrencyManager.getInstance()

        if (state.splitMode == 2 && state.currentToPay <= 0.0) {
            Toast.makeText(ctx, getString(R.string.select_item_for_print), Toast.LENGTH_SHORT).show()
            return
        }

        val digits = state.baseMinorUnitDigits ?: return
        val baseCurrency = state.baseCurrency ?: cm.selectedCurrency
        val totalDecimal = state.totalBaseMinor?.let {
            ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits)
        } ?: BigDecimal.ZERO
        val balanceDecimal = state.balanceBaseMinor?.let {
            ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits)
        } ?: BigDecimal.ZERO

        val printModel = CheckoutPrintModelFactory.from(
            state = state,
            tableNumber = tableNumber,
            transactionCurrency = cm.selectedCurrency,
            lines = viewModel.currentChargeItems().map { (item, quantity) -> CheckoutPrintLine(item, quantity) },
            comandaTotal = totalDecimal.toDouble(),
            paid = paidDecimal(state, digits),
            balance = balanceDecimal.toDouble()
        )
        val money: (Double) -> String = { formatBaseAmount(it, baseCurrency) }
        val renderedLines = printModel.lines.map { line ->
            val unit = line.item.product.selling_price ?: 0.0
            line.copy(unitDisplay = money(unit), subtotalDisplay = money(unit * line.selectedQuantity))
        }
        val renderedModel = printModel.copy(lines = renderedLines)
        val sb = CheckoutReceiptRenderer.render(ctx, renderedModel, money)
        PrinterHelper.printReceipt(requireContext(), sb)
    }

    private fun paidDecimal(state: CheckoutUiState, digits: Int): Double =
        state.paidBaseMinor?.let { ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits).toDouble() } ?: 0.0

    private fun printPaymentReceipt(
        method: String,
        amount: Double,
        authoritativeCurrency: String,
        allocations: List<com.plugpdv.pdv.models.ComandaPaymentAllocationDto>
    ) {
        val ctx = context ?: return
        val cm = CurrencyManager.getInstance()
        val paymentCurrency = authoritativeCurrency
        val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale(LanguageManager.getLanguage(ctx)))
        val dateStr = sdf.format(Date())
        
        val sb = StringBuilder()
        sb.append("================================\n")
        sb.append("      ").append(ctx.getString(R.string.print_payment_receipt_title)).append("  \n")
        sb.append("================================\n")
        sb.append(ctx.getString(R.string.print_date_label)).append(" ").append(dateStr).append("\n")
        val methodLabel = when (method.uppercase()) {
            "DINHEIRO", "CASH" -> ctx.getString(R.string.cash)
            "PIX", "PIX_TRANSFERENCIA" -> ctx.getString(R.string.pix)
            "CREDITO", "CREDIT", "CREDIT_INSTALLMENTS" -> ctx.getString(R.string.credit)
            "DEBITO", "DEBIT" -> ctx.getString(R.string.debit)
            else -> method
        }
        sb.append(ctx.getString(R.string.print_payment_method_label)).append(" ").append(methodLabel).append("\n")
        if (allocations.isNotEmpty()) {
            sb.append(ctx.getString(R.string.print_charge_items_label)).append("\n")
            allocations.forEach { allocation ->
                val name = allocation.name ?: ctx.getString(R.string.unnamed_product)
                val lineAmount = allocation.lineAmount?.let {
                    cm.formatExplicit(it, allocation.currency ?: paymentCurrency)
                }.orEmpty()
                sb.append(name).append(" x").append(allocation.quantity).append(" ").append(lineAmount).append("\n")
            }
        }
        sb.append(ctx.getString(R.string.print_paid_amount_label)).append(" ").append(cm.formatExplicit(amount, paymentCurrency)).append("\n")
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

}
