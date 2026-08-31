package com.plugpdv.pdv.ui.cashier

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import com.plugpdv.pdv.R
import com.plugpdv.pdv.databinding.ActivityCashierBinding
import com.plugpdv.pdv.hardware.HardwareFactory
import com.plugpdv.pdv.ui.BaseActivity
import com.plugpdv.pdv.ui.dashboard.OperatorDashboardActivity
import com.plugpdv.pdv.ui.sale.DirectSaleActivity
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.PrinterHelper
import dagger.hilt.android.AndroidEntryPoint
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.*

@AndroidEntryPoint
class CashierActivity : BaseActivity() {
    private lateinit var binding: ActivityCashierBinding
    private val viewModel: CashierViewModel by viewModels()
    private var token: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCashierBinding.inflate(layoutInflater)
        setContentView(binding.root)

        HardwareFactory.getPrinter(this)?.init()

        token = intent.getStringExtra("ACCESS_TOKEN")
        token?.let { viewModel.fetchHistory(it) }

        setupUI()
        observeViewModel()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val state = viewModel.cashierState.value
                val isOffline = viewModel.isOffline.value == true
                if (state is com.plugpdv.pdv.utils.CashierAuthorityState.CLOSED && !isOffline) {
                    Toast.makeText(this@CashierActivity, "Você precisa abrir o caixa para continuar.", Toast.LENGTH_SHORT).show()
                } else {
                    // Safe exit when OPEN, UNKNOWN, or offline
                    finish()
                }
            }
        })
    }

    private fun setupUI() {
        binding.etAmount.addTextChangedListener(CurrencyTextWatcher())
        binding.btnBack.setOnClickListener { finish() }
        binding.cardOpen.setOnClickListener { showConfirmation("abrir") }
        binding.cardSangria.setOnClickListener { showConfirmation("sangria") }
        binding.cardClose.setOnClickListener { showConfirmation("fechar") }

        binding.cardDashboard.setOnClickListener {
            val intent = Intent(this, OperatorDashboardActivity::class.java).apply {
                putExtra("ACCESS_TOKEN", token)
            }
            startActivity(intent)
        }

        binding.btnLogoff.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Logoff")
                .setMessage("Deseja mesmo sair da sua conta?")
                .setPositiveButton("Confirmar") { _, _ ->
                    performLogoff()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        binding.fabCurrency.setOnClickListener {
            showCurrencySelector {
                updateCurrencyLabel()
                binding.etAmount.setText("0,00")
            }
        }

        updateCurrencyLabel()
    }

    private fun performLogoff() {
        try {
            (applicationContext as? com.plugpdv.pdv.PlugPdvApplication)?.deviceGuardService?.stop()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        com.plugpdv.pdv.utils.CashierAuthorityStore.clearAuthority(this)

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(Constants.TOKEN)
            .remove(Constants.SESSION_ID)
            .remove(Constants.EMAIL)
            .remove(Constants.PASSWORD)
            .remove(Constants.HAS_MESA)
            .remove(Constants.HAS_VENDA_DIRETA)
            .remove(Constants.HAS_COMANDA)
            .remove(Constants.USER_ID)
            .remove(Constants.LOGIN_TIME)
            .apply()

        val intent = Intent(this, com.plugpdv.pdv.ui.auth.LoginActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        startActivity(intent)
        finish()
    }

    private fun getDefaultInitialAmount(currencyCode: String): String {
        val cap = com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider().getCapability(currencyCode)
        return if (cap.displayDecimals == 0) "0" else "0${cap.decimalSeparator}00"
    }

    private fun updateCurrencyLabel() {
        val code = CurrencyManager.getInstance().selectedCurrency
        binding.tvAmountLabel.text = "${getString(R.string.amount_label)} ($code)"
        binding.etAmount.setText(getDefaultInitialAmount(code))
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            setButtonsEnabled(!loading)
        }

        viewModel.cashierState.observe(this) {
            updateCashierUI()
        }

        viewModel.isOffline.observe(this) { isOffline ->
            if (isOffline) {
                Toast.makeText(this, "Sem conexão — exibindo estado salvo do caixa", Toast.LENGTH_SHORT).show()
            }
            updateCashierUI()
        }

        viewModel.currentSessionId.observe(this) { id ->
            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            if (id != null) {
                prefs.edit().putString(Constants.SESSION_ID, id).apply()
            } else {
                prefs.edit().remove(Constants.SESSION_ID).apply()
            }
        }

        viewModel.operationResult.observe(this) { result ->
            result?.let {
                when (it) {
                    is CashierResult.Success -> {
                        handleSuccess(it.action)
                    }
                    is CashierResult.Error -> {
                        Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                    }
                }
                viewModel.clearResult()
            }
        }
    }

    private fun updateCashierUI() {
        val state = viewModel.cashierState.value ?: com.plugpdv.pdv.utils.CashierAuthorityState.UNKNOWN()
        val isOffline = viewModel.isOffline.value == true

        when (state) {
            is com.plugpdv.pdv.utils.CashierAuthorityState.OPEN -> {
                binding.tvOpen.setText(R.string.cashier_already_open)
                binding.cardOpen.isEnabled = false
                binding.cardOpen.alpha = 0.5f
                binding.btnBack.visibility = View.VISIBLE

                if (isOffline) {
                    binding.cardSangria.isEnabled = false
                    binding.cardSangria.alpha = 0.5f
                    binding.cardClose.isEnabled = false
                    binding.cardClose.alpha = 0.5f
                } else {
                    binding.cardSangria.isEnabled = true
                    binding.cardSangria.alpha = 1.0f
                    binding.cardClose.isEnabled = true
                    binding.cardClose.alpha = 1.0f
                }
            }
            is com.plugpdv.pdv.utils.CashierAuthorityState.CLOSED -> {
                binding.tvOpen.setText(R.string.open_cashier)
                if (isOffline) {
                    binding.cardOpen.isEnabled = false
                    binding.cardOpen.alpha = 0.5f
                    binding.btnBack.visibility = View.VISIBLE
                } else {
                    binding.cardOpen.isEnabled = true
                    binding.cardOpen.alpha = 1.0f
                    binding.btnBack.visibility = View.GONE
                }
                binding.cardSangria.isEnabled = false
                binding.cardSangria.alpha = 0.5f
                binding.cardClose.isEnabled = false
                binding.cardClose.alpha = 0.5f
            }
            is com.plugpdv.pdv.utils.CashierAuthorityState.UNKNOWN -> {
                binding.tvOpen.text = if (isOffline) "Caixa Indisponível (Sem Conexão)" else getString(R.string.open_cashier)
                binding.btnBack.visibility = View.VISIBLE
                binding.cardOpen.isEnabled = !isOffline
                binding.cardOpen.alpha = if (isOffline) 0.5f else 1.0f
                binding.cardSangria.isEnabled = false
                binding.cardSangria.alpha = 0.5f
                binding.cardClose.isEnabled = false
                binding.cardClose.alpha = 0.5f
            }
        }
        setButtonsEnabled(binding.progressBar.visibility == View.GONE)
    }

    private fun handleSuccess(action: String) {
        val currency = CurrencyManager.getInstance().selectedCurrency
        val provider = com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider()
        
        val amountStr = binding.etAmount.text.toString()
        val cleanDigits = amountStr.replace("[^0-9]".toRegex(), "").toLongOrNull() ?: 0L
        val formattedWithSymbol = provider.formatMinorUnits(cleanDigits, currency)
        
        val ctx = com.plugpdv.pdv.utils.LanguageManager.updateResources(this, com.plugpdv.pdv.utils.LanguageManager.getLanguage(this))
        val lang = com.plugpdv.pdv.utils.LanguageManager.getLanguage(this)
        val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale(lang)).format(Date())
        val receiptText = "${ctx.getString(R.string.receipt_title, action.uppercase())}\n" +
                         "${ctx.getString(R.string.print_amount_label)} $formattedWithSymbol\n" +
                         ctx.getString(R.string.receipt_date, dateStr)
        
        PrinterHelper.printReceipt(ctx, receiptText)
        Toast.makeText(this, R.string.operation_success, Toast.LENGTH_SHORT).show()
        binding.etAmount.setText(getDefaultInitialAmount(currency))

        if (action.lowercase() == "abrir") {
            val intent = Intent(this, DirectSaleActivity::class.java).apply {
                putExtra("ACCESS_TOKEN", token)
            }
            startActivity(intent)
            finish()
        }
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        val isClosed = viewModel.isClosed.value == true
        binding.cardOpen.isEnabled = enabled && isClosed
        binding.cardOpen.alpha = if (enabled && isClosed) 1.0f else 0.5f
        
        binding.cardSangria.isEnabled = enabled && !isClosed
        binding.cardSangria.alpha = if (enabled && !isClosed) 1.0f else 0.5f
        
        binding.cardClose.isEnabled = enabled && !isClosed
        binding.cardClose.alpha = if (enabled && !isClosed) 1.0f else 0.5f
        
        binding.cardDashboard.isEnabled = enabled && !isClosed
        binding.cardDashboard.alpha = if (enabled && !isClosed) 1.0f else 0.5f
    }

    private fun showConfirmation(action: String) {
        val titleRes = when (action.lowercase()) {
            "abrir" -> R.string.confirm_op_title_open
            "sangria" -> R.string.confirm_op_title_sangria
            else -> R.string.confirm_op_title_close
        }
        
        val currency = CurrencyManager.getInstance().selectedCurrency
        val provider = com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider()
        val cap = provider.getCapability(currency)

        val amountStr = binding.etAmount.text.toString().ifEmpty { getDefaultInitialAmount(currency) }
        val cleanDigits = amountStr.replace("[^0-9]".toRegex(), "").toLongOrNull() ?: 0L
        val formattedWithSymbol = provider.formatMinorUnits(cleanDigits, currency)

        val divisor = Math.pow(10.0, cap.displayDecimals.toDouble())
        val valorDouble = if (divisor > 0) cleanDigits.toDouble() / divisor else cleanDigits.toDouble()

        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(getString(R.string.confirm_op_msg, formattedWithSymbol))
            .setPositiveButton(R.string.confirm) { _, _ ->
                token?.let { viewModel.performOperation(it, action, valorDouble) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private inner class CurrencyTextWatcher : TextWatcher {
        private var current = ""
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable) {
            if (s.toString() != current) {
                binding.etAmount.removeTextChangedListener(this)
                val cleanString = s.toString().replace("[^0-9]".toRegex(), "")
                if (cleanString.isNotEmpty()) {
                    val currency = CurrencyManager.getInstance().selectedCurrency
                    val provider = com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider()
                    val cap = provider.getCapability(currency)
                    val minorUnits = cleanString.toLongOrNull() ?: 0L

                    val formattedNumber = if (cap.displayDecimals > 0) {
                        val divisor = Math.pow(10.0, cap.displayDecimals.toDouble()).toLong()
                        val integerPart = minorUnits / divisor
                        val decimalPart = (minorUnits % divisor).toString().padStart(cap.displayDecimals, '0')
                        val integerFormatted = formatIntegerWithSeparator(integerPart, cap.thousandsSeparator)
                        "$integerFormatted${cap.decimalSeparator}$decimalPart"
                    } else {
                        formatIntegerWithSeparator(minorUnits, cap.thousandsSeparator)
                    }

                    current = formattedNumber
                    binding.etAmount.setText(formattedNumber)
                    binding.etAmount.setSelection(formattedNumber.length)
                } else {
                    current = ""
                    binding.etAmount.setText("")
                }
                binding.etAmount.addTextChangedListener(this)
            }
        }

        private fun formatIntegerWithSeparator(number: Long, separator: String): String {
            val str = number.toString()
            val builder = StringBuilder()
            var count = 0
            for (i in str.length - 1 downTo 0) {
                builder.append(str[i])
                count++
                if (count % 3 == 0 && i != 0) {
                    builder.append(separator)
                }
            }
            return builder.reverse().toString()
        }
    }
}
