package com.plugpdv.pdv.ui.cashier

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import com.plugpdv.pdv.R
import com.plugpdv.pdv.databinding.ActivityCashierBinding
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.ui.BaseActivity
import com.plugpdv.pdv.utils.*
import dagger.hilt.android.AndroidEntryPoint
import java.math.BigDecimal

@AndroidEntryPoint
class CashierActivity : BaseActivity() {
    private lateinit var binding: ActivityCashierBinding
    private val viewModel: CashierViewModel by viewModels()
    private val rules = DefaultCurrencyRulesProvider()
    private var token: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCashierBinding.inflate(layoutInflater)
        setContentView(binding.root)
        token = intent.getStringExtra("ACCESS_TOKEN")
        binding.btnBack.setOnClickListener { finish() }
        binding.btnLogoff.setOnClickListener { confirmLogoff() }
        binding.btnOpen.setOnClickListener { showOpenDialog() }
        binding.btnPayIn.setOnClickListener { showMovementDialog("pay_in") }
        binding.btnWithdrawal.setOnClickListener { showMovementDialog("sangria") }
        binding.btnClose.setOnClickListener { token?.let(viewModel::previewClose) }
        observe()
        token?.let(viewModel::refresh)
    }

    private fun observe() {
        viewModel.session.observe(this) { render(it) }
        viewModel.loaded.observe(this) { render(viewModel.session.value) }
        viewModel.isOffline.observe(this) { render(viewModel.session.value) }
        viewModel.loading.observe(this) {
            binding.progressBar.visibility = if (it == null) View.GONE else View.VISIBLE
            render(viewModel.session.value)
        }
        viewModel.result.observe(this) { result ->
            when (result) {
                is CashierResult.Opened -> printOpening(result.response)
                is CashierResult.MovementCreated -> printMovement(result.action, result.response)
                is CashierResult.PreviewReady -> showClosingDialog(result.snapshot)
                is CashierResult.Closed -> { printZ(result.snapshot); render(null) }
                is CashierResult.Error -> Toast.makeText(this, localizedError(result), Toast.LENGTH_LONG).show()
                null -> Unit
            }
            if (result != null) viewModel.clearResult()
        }
    }

    private fun render(session: CashSessionSnapshot?) {
        val loaded = viewModel.loaded.value == true
        val busy = viewModel.loading.value != null
        val closing = viewModel.loading.value == CashLoading.CLOSE
        val offline = viewModel.isOffline.value == true
        binding.sessionContent.removeAllViews()
        binding.tvStatus.text = when {
            closing -> getString(R.string.cash_closing)
            !loaded -> getString(R.string.cash_loading)
            session == null -> getString(R.string.cash_closed)
            else -> getString(R.string.cash_status_value, localizedStatus(session.status))
        }
        binding.tvMetadata.text = session?.let {
            getString(R.string.cash_session_metadata, displayDate(it.openedAt), it.operatorName ?: it.operatorId.orEmpty())
        }.orEmpty()
        if (!closing) session?.cashPositions()?.forEach { addPosition(it) }
        if (!closing && session != null) addPayments(session.paymentSummary())
        binding.btnOpen.isEnabled = loaded && session == null && !busy && !offline
        binding.btnPayIn.isEnabled = session != null && !busy && !offline
        binding.btnWithdrawal.isEnabled = session != null && !busy && !offline
        binding.btnClose.isEnabled = session != null && !busy && !offline
        binding.tvUnavailableReason.visibility = if (offline) View.VISIBLE else View.GONE
        binding.tvUnavailableReason.text = getString(R.string.cash_offline_reason)
    }

    private fun addPosition(p: CashPosition) {
        val box = verticalBox()
        box.addView(text(p.currency, 19, true))
        box.addView(text(getString(R.string.cash_expected), 12, true))
        box.addView(text(formatIn(p.expectedCash, p.currency), 25, true))
        addMoneyRow(box, R.string.cash_opening, p.opening, p.currency)
        addMoneyRow(box, R.string.cash_receipts, p.cashReceipts, p.currency)
        addMoneyRow(box, R.string.cash_payins, p.cashPayIns, p.currency)
        addMoneyRow(box, R.string.cash_withdrawals, p.withdrawals, p.currency)
        addMoneyRow(box, R.string.cash_refunds, p.cashRefunds, p.currency)
        binding.sessionContent.addView(box)
    }

    private fun addPayments(payments: List<CashPaymentSummary>) {
        val box = verticalBox()
        box.addView(text(getString(R.string.cash_payments_session), 17, true))
        if (payments.isEmpty()) box.addView(text(getString(R.string.cash_no_payments), 14, false))
        payments.forEach {
            box.addView(text(localizedMethod(it.paymentMethod), 13, true))
            box.addView(text(formatIn(it.amount, it.currency), 18, false))
        }
        binding.sessionContent.addView(box)
    }

    private fun showOpenDialog() {
        val wrap = dialogForm()
        val currency = Button(this).apply { text = CurrencyManager.getInstance().selectedCurrency }
        val amount = amountInput()
        val reason = textInput(R.string.cash_reason_optional)
        wrap.addView(label(R.string.currency)); wrap.addView(currency)
        wrap.addView(label(R.string.cash_opening_amount)); wrap.addView(amount); wrap.addView(reason)
        currency.setOnClickListener { showCurrencySelector { currency.text = CurrencyManager.getInstance().selectedCurrency } }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.cash_open_title).setView(wrap)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.cash_action_open, null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = parseAmount(amount.text.toString(), currency.text.toString())
            if (value == null || value < BigDecimal.ZERO) amount.error = getString(R.string.cash_invalid_amount)
            else { dialog.dismiss(); token?.let { viewModel.open(it, currency.text.toString(), value, reason.text.toString().ifBlank { null }) } }
        } }
        dialog.show()
    }

    private fun showMovementDialog(action: String) {
        val currencies = viewModel.session.value?.cashPositions()?.map { it.currency }.orEmpty()
        if (currencies.isEmpty()) return
        val wrap = dialogForm()
        val currency = Spinner(this).apply { adapter = ArrayAdapter(this@CashierActivity, android.R.layout.simple_spinner_dropdown_item, currencies) }
        val amount = amountInput(); val reason = textInput(R.string.cash_reason_required)
        val reference = if (action == "pay_in") textInput(R.string.cash_reference_required) else null
        wrap.addView(label(R.string.currency)); wrap.addView(currency); wrap.addView(label(R.string.amount_label)); wrap.addView(amount); wrap.addView(reason)
        reference?.let(wrap::addView)
        val title = if (action == "pay_in") R.string.cash_payin_title else R.string.cash_withdrawal_title
        val button = if (action == "pay_in") R.string.cash_action_payin else R.string.cash_action_withdrawal
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(wrap).setNegativeButton(R.string.cancel, null)
            .setPositiveButton(button, null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val curr = currency.selectedItem.toString(); val value = parseAmount(amount.text.toString(), curr)
            val refMissing = reference != null && reference.text.isBlank()
            when { value == null || value <= BigDecimal.ZERO -> amount.error = getString(R.string.cash_invalid_amount)
                reason.text.isBlank() -> reason.error = getString(R.string.cash_required)
                refMissing -> reference?.error = getString(R.string.cash_required)
                else -> { dialog.dismiss(); token?.let { viewModel.movement(it, action, curr, value, reason.text.toString(), reference?.text?.toString()) } }
            }
        } }
        dialog.show()
    }

    private fun showClosingDialog(preview: CashSessionSnapshot) {
        val wrap = dialogForm(); val inputs = linkedMapOf<String, EditText>()
        preview.cashPositions().forEach { p ->
            wrap.addView(text(p.currency, 18, true))
            listOf(R.string.cash_opening to p.opening, R.string.cash_receipts to p.cashReceipts,
                R.string.cash_payins to p.cashPayIns, R.string.cash_withdrawals to p.withdrawals,
                R.string.cash_refunds to p.cashRefunds, R.string.cash_expected to p.expectedCash)
                .forEach { (label, amount) -> addMoneyRow(wrap, label, amount, p.currency) }
            val input = amountInput().apply { hint = getString(R.string.cash_counted_blank_hint) }
            wrap.addView(label(R.string.cash_counted)); wrap.addView(input); inputs[p.currency] = input
        }
        val first = AlertDialog.Builder(this).setTitle(R.string.cash_close_title).setView(wrap)
            .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.confirm, null).create()
        first.setOnShowListener { first.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val invalid = inputs.any { (currency, input) -> input.text.isNotBlank() && parseAmount(input.text.toString(), currency) == null }
            if (invalid) { Toast.makeText(this, R.string.cash_invalid_amount, Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            val counted = inputs.mapNotNull { (currency, input) ->
                if (input.text.isBlank()) null else CountedCashRequest(currency, parseAmount(input.text.toString(), currency))
            }.ifEmpty { null }
            first.dismiss()
            AlertDialog.Builder(this).setTitle(R.string.cash_close_confirm)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.cash_action_close) { _, _ -> token?.let { viewModel.close(it, counted) } }.show()
        } }
        first.show()
    }

    private fun printOpening(r: CashActionResponse) {
        val currency = r.openingCurrency ?: r.currency ?: return
        val amount = r.openingAmount ?: r.amount ?: BigDecimal.ZERO
        PrinterHelper.printReceipt(this, buildString {
            append(getString(R.string.cash_receipt_opening)).append('\n')
            receiptMeta(r.sessionId, r.operatorName ?: r.operatorId, r.terminalId, r.openedAt).forEach { append(it).append('\n') }
            append(currency).append('\n').append(formatIn(amount, currency)).append("\n\n\n\n")
        })
    }

    private fun printMovement(action: String, r: CashActionResponse) {
        val currency = r.currency ?: return
        val title = if (action == "sangria") getString(R.string.cash_receipt_withdrawal) else getString(R.string.cash_receipt_payin)
        val movement = viewModel.session.value?.movements?.lastOrNull { it.operationId == r.operationId }
        PrinterHelper.printReceipt(this, buildString {
            append(title).append('\n'); append(getString(R.string.cash_operation_id, r.operationId.orEmpty())).append('\n')
            append(getString(R.string.cash_session_id, r.sessionId)).append('\n')
            append(getString(R.string.cash_operator, movement?.operatorName ?: movement?.operatorId.orEmpty())).append('\n')
            append(getString(R.string.cash_datetime, displayDate(movement?.createdAt))).append('\n')
            append(currency).append(' ').append(formatIn(r.amount ?: BigDecimal.ZERO, currency)).append('\n')
            append(getString(R.string.cash_reason, movement?.reason.orEmpty())).append("\n\n\n\n")
        })
    }

    private fun printZ(s: CashSessionSnapshot) {
        PrinterHelper.printReceipt(this, buildString {
            append(getString(R.string.cash_receipt_closing)).append("\nZ REPORT\n")
            receiptMeta(s.sessionId, s.operatorName ?: s.operatorId, s.terminalId, s.openedAt).forEach { append(it).append('\n') }
            append(getString(R.string.cash_closed_at, displayDate(s.closedAt))).append('\n')
            s.cashPositions().forEach { p ->
                append("\n").append(p.currency).append('\n')
                listOf(R.string.cash_opening to p.opening, R.string.cash_receipts to p.cashReceipts,
                    R.string.cash_payins to p.cashPayIns, R.string.cash_withdrawals to p.withdrawals,
                    R.string.cash_refunds to p.cashRefunds, R.string.cash_expected to p.expectedCash)
                    .forEach { (label, value) -> append(getString(label)).append(": ").append(formatIn(value, p.currency)).append('\n') }
                append(getString(R.string.cash_counted)).append(": ").append(p.countedCash?.let { formatIn(it, p.currency) } ?: "—").append('\n')
                append(getString(R.string.cash_variance)).append(": ").append(p.variance?.let { formatIn(it, p.currency) } ?: "—").append('\n')
            }
            append("\nPAYMENTS BY METHOD\n")
            s.paymentSummary().forEach { append(it.paymentMethod).append(' ').append(it.currency).append(' ').append(formatIn(it.amount, it.currency)).append('\n') }
            append("\n\n\n\n")
        })
    }

    internal fun parseAmount(raw: String, currency: String): BigDecimal? = try {
        val cap = rules.getCapability(currency)
        val normalized = raw.trim().replace(cap.thousandsSeparator, "").replace(cap.decimalSeparator, ".")
        normalized.takeIf { it.isNotBlank() }?.let(::BigDecimal)
    } catch (_: Exception) { null }

    internal fun formatIn(amount: BigDecimal, currency: String): String {
        val cap = rules.getCapability(currency)
        val plain = amount.setScale(cap.displayDecimals).toPlainString()
        val parts = plain.split('.')
        val grouped = parts[0].reversed().chunked(3).joinToString(cap.thousandsSeparator).reversed()
        val number = if (cap.displayDecimals == 0) grouped else grouped + cap.decimalSeparator + parts.getOrElse(1) { "" }.padEnd(cap.displayDecimals, '0')
        return if (cap.symbolPosition.equals("SUFFIX", true)) "$number ${cap.symbol}" else "${cap.symbol} $number"
    }

    private fun receiptMeta(id: String, operator: String?, terminal: String?, date: String?) = listOf(
        getString(R.string.cash_session_id, id), getString(R.string.cash_operator, operator.orEmpty()),
        getString(R.string.cash_terminal, terminal.orEmpty()), getString(R.string.cash_datetime, displayDate(date)))
    private fun displayDate(value: String?) = value?.replace('T', ' ')?.substringBefore('.')?.removeSuffix("Z").orEmpty()
    private fun localizedStatus(status: String) = if (status.equals("OPEN", true)) getString(R.string.cash_open_status) else if (status.equals("CLOSED", true)) getString(R.string.cash_closed) else status
    private fun localizedMethod(method: String) = when (method.uppercase()) { "DINHEIRO", "CASH" -> getString(R.string.cash); else -> method.replace('_', ' ') }
    private fun localizedError(e: CashierResult.Error): String {
        val name = e.messageKey?.replace('.', '_')
        val id = name?.let { resources.getIdentifier(it, "string", packageName) } ?: 0
        return if (id != 0) getString(id) else getString(if (e.unexpected) R.string.cash_error_unexpected else R.string.cash_error_business, e.code.orEmpty())
    }
    private fun verticalBox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 14, 16, 14); setBackgroundColor(0xFFF8FAFC.toInt());
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 14 } }
    private fun text(value: String, size: Int, bold: Boolean) = TextView(this).apply { text = value; textSize = size.toFloat(); if (bold) setTypeface(typeface, Typeface.BOLD); setTextColor(0xFF111827.toInt()) }
    private fun addMoneyRow(parent: LinearLayout, label: Int, value: BigDecimal, currency: String) { parent.addView(text("${getString(label)}\n${formatIn(value, currency)}", 14, false)) }
    private fun dialogForm() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 8, 48, 0) }
    private fun amountInput() = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL }
    private fun textInput(hint: Int) = EditText(this).apply { setHint(hint); inputType = InputType.TYPE_CLASS_TEXT }
    private fun label(id: Int) = text(getString(id), 13, true)

    private fun confirmLogoff() { AlertDialog.Builder(this).setTitle(R.string.logout).setMessage(R.string.logout_confirmation)
        .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.confirm) { _, _ -> performLogoff() }.show() }
    private fun performLogoff() {
        CashierAuthorityStore.clearAuthority(this)
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
        startActivity(Intent(this, com.plugpdv.pdv.ui.auth.LoginActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK })
        finish()
    }
}
