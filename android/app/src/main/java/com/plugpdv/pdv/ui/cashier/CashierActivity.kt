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
                if (viewModel.isClosed.value == true) {
                    Toast.makeText(this@CashierActivity, "Você precisa abrir o caixa para continuar.", Toast.LENGTH_SHORT).show()
                } else {
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

    private fun updateCurrencyLabel() {
        val code = CurrencyManager.getInstance().selectedCurrency
        binding.tvAmountLabel.text = "${getString(R.string.amount_label)} ($code)"
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            setButtonsEnabled(!loading)
        }

        viewModel.isClosed.observe(this) { isClosed ->
            if (isClosed) {
                binding.tvOpen.setText(R.string.open_cashier)
                binding.cardOpen.isEnabled = true
                binding.cardOpen.alpha = 1.0f
                binding.cardOpen.visibility = View.VISIBLE
                binding.btnBack.visibility = View.GONE
                binding.cardSangria.isEnabled = false
                binding.cardSangria.alpha = 0.5f
                binding.cardClose.isEnabled = false
                binding.cardClose.alpha = 0.5f
            } else {
                binding.tvOpen.setText(R.string.cashier_already_open)
                binding.cardOpen.isEnabled = false
                binding.cardOpen.alpha = 0.5f
                binding.btnBack.visibility = View.VISIBLE
                binding.cardSangria.isEnabled = true
                binding.cardSangria.alpha = 1.0f
                binding.cardClose.isEnabled = true
                binding.cardClose.alpha = 1.0f
            }
            
            // Atualiza os botões toda vez que o status mudar
            setButtonsEnabled(binding.progressBar.visibility == View.GONE)
        }

        viewModel.currentSessionId.observe(this) { id ->
            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            if (id != null) {
                prefs.edit().putString("SESSION_ID", id).apply()
            } else {
                prefs.edit().remove("SESSION_ID").apply()
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

    private fun handleSuccess(action: String) {
        val amountStr = binding.etAmount.text.toString()
        val currency = CurrencyManager.getInstance().selectedCurrency
        
        val receiptText = "${getString(R.string.receipt_title, action.uppercase())}\n" +
                         "VALOR: $currency $amountStr\n" +
                         getString(R.string.receipt_date, Date().toString())
        
        PrinterHelper.printReceipt(this, receiptText)
        Toast.makeText(this, R.string.operation_success, Toast.LENGTH_SHORT).show()
        binding.etAmount.setText("0,00")

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
        
        val amountStr = binding.etAmount.text.toString().ifEmpty { "0,00" }
        val currency = CurrencyManager.getInstance().selectedCurrency

        AlertDialog.Builder(this)
            .setTitle(titleRes)
            .setMessage(getString(R.string.confirm_op_msg, "$currency $amountStr"))
            .setPositiveButton(R.string.confirm) { _, _ ->
                val valor = amountStr.replace("[^0-9]".toRegex(), "").toDoubleOrNull() ?: 0.0
                token?.let { viewModel.performOperation(it, action, valor / 100.0) }
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
                    val parsed = cleanString.toDouble()
                    val formatted = NumberFormat.getCurrencyInstance(Locale("pt", "BR")).format(parsed / 100)
                    val readable = formatted.replace("[R$]".toRegex(), "").trim()
                    current = readable
                    binding.etAmount.setText(readable)
                    binding.etAmount.setSelection(readable.length)
                } else {
                    current = ""
                    binding.etAmount.setText("")
                }
                binding.etAmount.addTextChangedListener(this)
            }
        }
    }
}
