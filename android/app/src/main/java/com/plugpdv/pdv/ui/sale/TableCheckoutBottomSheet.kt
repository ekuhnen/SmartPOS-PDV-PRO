package com.plugpdv.pdv.ui.sale

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Bundle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
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
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.PaymentMethod
import com.plugpdv.pdv.utils.PrinterHelper
import com.plugpdv.pdv.utils.TableManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class TableCheckoutBottomSheet : BottomSheetDialogFragment() {
    private var table: Table? = null
    private var token: String? = null
    private var binding: LayoutTableCheckoutBinding? = null
    
    private val viewModel: CheckoutViewModel by viewModels()

    private val paymentLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val status = result.data?.getStringExtra("status")
            val methodStr = result.data?.getStringExtra("method") ?: "PIX"
            if ("APPROVED" == status) {
                val method = PaymentMethod.fromString(methodStr)
                viewModel.finalizePayment(method)
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
        arguments?.let {
            val tableNumber = it.getInt("TABLE_NUMBER")
            table = TableManager.getTableByNumber(tableNumber)
            token = it.getString("TOKEN")
        }
        
        if (table == null || token == null) {
            dismiss()
            return
        }

        val prefs = requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        viewModel.init(
            table!!, 
            token!!,
            prefs.getString(Constants.SESSION_ID, null),
            prefs.getString(Constants.OPERATOR_ID, null),
            prefs.getString(Constants.OPERATOR_NAME, null)
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

        b.tvPendingBalance.text = cm.format(table?.getPendingBalance() ?: 0.0)
        b.tvTotalToPay.text = cm.format(state.finalToPay)
        b.loadingLayout.loadingOverlay.visibility = if (state.isLoading) View.VISIBLE else View.GONE
        b.btnPayLink.isEnabled = !state.isLoading

        if (state.paymentSuccess) {
            onUpdateNotify()
            
            // Print the transaction receipt automatically
            state.lastPaymentMethod?.let { method ->
                printPaymentReceipt(method, state.lastPaymentAmount)
            }
            
            if (table?.getPendingBalance() ?: 1.0 <= 0.01) {
                viewModel.acknowledgePaymentSuccess()
                
                val currentTable = table
                val totalFactura = state.fullTableTotalPaid
                
                FacturaElectronicaDialog(requireContext()) { emitir ->
                    if (emitir) {
                        val prefs = requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                        val operatorName = prefs.getString(Constants.OPERATOR_NAME, "Operador")
                        val cm = CurrencyManager.getInstance()
                        PrinterHelper.printMockFactura(
                            context = requireContext(),
                            total = totalFactura,
                            currency = cm.selectedCurrency,
                            operatorName = operatorName
                        )
                    }
                    dismiss()
                    if (currentTable?.status == Table.Status.AVAILABLE || TableManager.getTableByNumber(currentTable?.number ?: 0)?.status == Table.Status.AVAILABLE) {
                        activity?.finish()
                    }
                }.show()
                
            } else {
                Toast.makeText(context, "Pagamento parcial aprovado!", Toast.LENGTH_SHORT).show()
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
        lockModeIfPaymentStarted()
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
        
        addBreakdownRow(getString(R.string.subtotal), cm.format(state.currentToPay))
        
        val currentCurrency = cm.selectedCurrency
        state.activeTaxes.filter { it.currency.equals(currentCurrency, ignoreCase = true) }.forEach { tax ->
            val calculatedTax = state.currentToPay * (tax.percentage / 100.0)
            val label = "${tax.name} (${String.format("%.1f%%", tax.percentage)})"
            addBreakdownRow(label, cm.format(calculatedTax))
        }

        // Add Service Fee — mostra se allow_override=true (independente de fixed_enabled)
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

    private fun lockModeIfPaymentStarted() {
        val b = binding ?: return
        val currentTable = table ?: return
        
        val itemsPaid = currentTable.items.any { it.paidQuantity > 0 }
        val moneyPaid = currentTable.paidAmount > 0
        
        if (itemsPaid) {
            // Force Items mode and disable others
            if (viewModel.uiState.value.splitMode != 2) {
                viewModel.setSplitMode(2)
                b.toggleGroupMode.check(R.id.btnModeSplitItems)
            }
            b.btnModeFull.isEnabled = false
            b.btnModeSplitPeople.isEnabled = false
            b.btnModeSplitItems.isEnabled = true
        } else if (moneyPaid) {
            // Force Total or People mode, disable Items
            b.btnModeSplitItems.isEnabled = false
            // Allow toggling between Full and People if they were used interchangeably
        }
    }

    private fun finalizePayment() {
        val state = viewModel.uiState.value
        if (state.currentToPay <= 0) {
            Toast.makeText(context, "Valor inválido", Toast.LENGTH_SHORT).show()
            return
        }

        PaymentMethodSelectorBottomSheet.newInstance(state.finalToPay) { method, amount ->
            Log.d("TableCheckoutBottomSheet", "Método selecionado: $method, Valor Pago: $amount")
            when (method) {
                PaymentMethodSelectorBottomSheet.PaymentType.CASH -> {
                    viewModel.finalizePayment(PaymentMethod.CASH, amount)
                }
                PaymentMethodSelectorBottomSheet.PaymentType.PLUG_PAY -> {
                    val cm = CurrencyManager.getInstance()
                    val convertedAmount = cm.convert(amount)
                    val prefs = requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    val operatorId = prefs.getString(Constants.OPERATOR_ID, null)

                    val activeTaxes = viewModel.uiState.value.activeTaxes
                    val amountsJsonStr = com.plugpdv.pdv.utils.PaymentHelper.generateAmountsJson(amount, cm.selectedCurrency, activeTaxes, cm)

                    val intent = Intent(context, PaymentHandlerActivity::class.java).apply {
                        putExtra(PaymentHandlerActivity.EXTRA_AMOUNT, convertedAmount.toString())
                        putExtra(PaymentHandlerActivity.EXTRA_AMOUNT_BRL, amount.toString())
                        putExtra(PaymentHandlerActivity.EXTRA_AMOUNTS_JSON, amountsJsonStr)
                        putExtra(PaymentHandlerActivity.EXTRA_ORDER_ID, table?.comandaId?.toString() ?: "0")
                        putExtra(PaymentHandlerActivity.EXTRA_TABLE_NUMBER, table?.number ?: 0)
                        putExtra(PaymentHandlerActivity.EXTRA_MERCHANT_ID, operatorId ?: "merchant123")
                        putExtra(PaymentHandlerActivity.EXTRA_DESCRIPTION, "Mesa ${table?.number ?: ""}")
                    }
                    paymentLauncher.launch(intent)
                }
            }
        }.show(childFragmentManager, "payment_selector")
    }

    private fun printTableReceipt() {
        val currentTable = table ?: return
        val state = viewModel.uiState.value
        val sb = StringBuilder()
        val cm = CurrencyManager.getInstance()
        val currentCurrency = cm.selectedCurrency

        sb.append("MESA: ").append(currentTable.number).append("\n")
        if (!currentTable.customerName.isNullOrEmpty()) {
            sb.append("CLIENTE: ").append(currentTable.customerName).append("\n")
        }
        sb.append("--------------------------------\n")

        val listToPrint = mutableListOf<TableItem>()
        if (state.splitMode == 0 || state.splitMode == 1) {
            currentTable.items.filter { !it.isPaid && !it.removed }.forEach { listToPrint.add(it) }
        } else if (state.splitMode == 2) {
            viewModel.itemsToPay.filter { it.selected }.forEach { tip ->
                listToPrint.add(TableItem(product = tip.item.product, quantity = tip.selectedQuantity))
            }
        }

        if (listToPrint.isEmpty()) {
            Toast.makeText(context, "Nenhum item para imprimir", Toast.LENGTH_SHORT).show()
            return
        }

        listToPrint.forEach { item ->
            var name = item.product.name ?: ""
            if (name.length > 18) name = name.substring(0, 15) + "..."
            sb.append(String.format("%-18s %2d x %s\n", name, item.quantity, cm.format(item.product.selling_price ?: 0.0)))
            sb.append(String.format("%31s\n", cm.format((item.product.selling_price ?: 0.0) * item.quantity.toDouble())))
        }

        sb.append("--------------------------------\n")
        sb.append(String.format("%-18s %13s\n", "SUBTOTAL:", cm.format(state.currentToPay)))

        state.activeTaxes.filter { it.currency.equals(currentCurrency, ignoreCase = true) }.forEach { tax ->
            val calculatedTax = state.currentToPay * (tax.percentage / 100.0)
            val label = "${tax.name} (${String.format("%.1f%%", tax.percentage)}):"
            sb.append(String.format("%-20s %11s\n", label, cm.format(calculatedTax)))
        }

        if (state.serviceFeeAmount > 0) {
            sb.append(String.format("%-20s %11s\n", "TAXA DE SERVICO:", cm.format(state.serviceFeeAmount)))
        }

        sb.append("--------------------------------\n")
        sb.append(String.format("%-15s %16s\n", "TOTAL:", cm.format(state.finalToPay)))

        if (state.splitMode == 1) {
            val peopleStr = binding?.etPeopleCount?.text.toString()
            val people = peopleStr.toIntOrNull() ?: 1
            if (people > 1) {
                val perPerson = state.finalToPay / people
                sb.append("--------------------------------\n")
                sb.append("DIVIDIDO POR $people PESSOAS\n")
                sb.append(String.format("%-15s %16s\n", "VALOR POR PESSOA:", cm.format(perPerson)))
            }
        }

        context?.let { PrinterHelper.printReceipt(it, sb.toString()) }
    }

    private fun printPaymentReceipt(method: String, amountPaid: Double) {
        val currentTable = table ?: return
        val sb = java.lang.StringBuilder()
        val cm = CurrencyManager.getInstance()
        val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.getDefault()).format(Date())
        
        sb.append("COMPROVANTE DE PAGAMENTO\n")
        sb.append("--------------------------------\n")
        sb.append("MESA: ").append(currentTable.number).append("\n")
        sb.append("DATA: ").append(dateStr).append("\n")
        sb.append("VALOR PAGO: ").append(cm.format(amountPaid)).append("\n")
        sb.append("METODO: ").append(method).append("\n")
        
        val prefs = requireContext().getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val operatorName = prefs.getString(Constants.OPERATOR_NAME, "Operador")
        sb.append("OPERADOR: ").append(operatorName).append("\n")
        sb.append("--------------------------------\n")
        
        context?.let { PrinterHelper.printReceipt(it, sb.toString()) }
    }

    private fun refreshUI() {
        // Recalculate taxes and totals without resetting split state (items selected or people count)
        viewModel.refreshCalculations()
    }

    private fun onUpdateNotify() {
        (activity as? com.plugpdv.pdv.ui.sale.TableOrderActivity)?.updateUI()
        (activity as? com.plugpdv.pdv.ui.sale.CommandOrderActivity)?.updateUI()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding = null
    }

    companion object {
        @JvmStatic
        fun newInstance(tableNumber: Int, token: String): TableCheckoutBottomSheet {
            return TableCheckoutBottomSheet().apply {
                arguments = Bundle().apply {
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
            holder.binding.tvItemValue.text = CurrencyManager.getInstance().format(tip.item.product.selling_price ?: 0.0)
            holder.binding.tvItemSubtotal.text = CurrencyManager.getInstance().format((tip.item.product.selling_price ?: 0.0) * tip.selectedQuantity)
            
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
