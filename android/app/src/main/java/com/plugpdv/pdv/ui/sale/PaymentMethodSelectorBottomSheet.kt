package com.plugpdv.pdv.ui.sale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.plugpdv.pdv.databinding.LayoutPaymentMethodSelectorBinding
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.MoneyDecimal
import com.plugpdv.pdv.utils.MoneyQuote
import java.math.BigDecimal

data class SelectedPaymentQuote(
    val transactionAmount: BigDecimal,
    val transactionCurrency: String,
    val baseAmount: BigDecimal,
    val baseCurrency: String,
    val fxRate: BigDecimal,
    val snapshot: Map<String, String>? = null
) {
    fun toMoneyQuote(): MoneyQuote = MoneyQuote(
        transactionAmount = transactionAmount,
        transactionCurrency = transactionCurrency,
        baseAmount = baseAmount,
        baseCurrency = baseCurrency,
        fxRate = fxRate,
        snapshot = snapshot
    )
}

class PaymentMethodSelectorBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutPaymentMethodSelectorBinding? = null
    private val binding get() = _binding!!

    private var baseAmount: BigDecimal = BigDecimal.ZERO
    private var baseCurrency: String = "BRL"
    private var onQuoteSelected: ((PaymentType, SelectedPaymentQuote) -> Unit)? = null

    enum class PaymentType {
        CASH, PLUG_PAY
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val amountDbl = arguments?.getDouble(ARG_TOTAL) ?: 0.0
        baseAmount = MoneyDecimal.of(amountDbl)
        baseCurrency = arguments?.getString(ARG_BASE_CURRENCY) ?: CurrencyManager.getInstance().getBaseCurrency()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutPaymentMethodSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cm = CurrencyManager.getInstance()
        val selectedTxCurrency = cm.selectedCurrency

        binding.tilAmount.prefixText = "$selectedTxCurrency "

        // Calcula quote determinística inicial a partir do baseAmount
        val quoteResult = cm.convertMoneyExact(
            amount = baseAmount,
            fromCurrency = baseCurrency,
            toCurrency = selectedTxCurrency,
            baseCurrency = baseCurrency
        )

        var currentQuote: SelectedPaymentQuote? = null

        if (quoteResult.isSuccess) {
            val q = quoteResult.getOrThrow()
            currentQuote = SelectedPaymentQuote(
                transactionAmount = q.transactionAmount,
                transactionCurrency = q.transactionCurrency,
                baseAmount = q.baseAmount,
                baseCurrency = q.baseCurrency,
                fxRate = q.fxRate,
                snapshot = q.snapshot
            )
            val displayDecimals = MoneyDecimal.getDisplayDecimals(selectedTxCurrency)
            val formatted = if (displayDecimals == 0) {
                q.transactionAmount.toBigInteger().toString()
            } else {
                q.transactionAmount.setScale(displayDecimals, java.math.RoundingMode.HALF_UP).toPlainString()
            }
            binding.etAmount.setText(formatted)
        } else {
            val errorMsg = quoteResult.exceptionOrNull()?.message ?: "FX_RATE_MISSING"
            binding.etAmount.setText("")
            binding.tilAmount.error = errorMsg
            binding.cardCash.isEnabled = false
            binding.cardCash.alpha = 0.5f
            binding.cardPlugPay.isEnabled = false
            binding.cardPlugPay.alpha = 0.5f
            Toast.makeText(requireContext(), "Cotação ausente para $selectedTxCurrency: $errorMsg", Toast.LENGTH_LONG).show()
        }

        fun resolveFinalQuote(): SelectedPaymentQuote? {
            val text = binding.etAmount.text?.toString()?.replace(",", ".")?.trim()
            val enteredTxAmount = text?.let { runCatching { BigDecimal(it) }.getOrNull() }
            if (enteredTxAmount == null) return currentQuote

            val recalculated = cm.quoteTransactionAmount(
                transactionAmount = enteredTxAmount,
                transactionCurrency = selectedTxCurrency,
                baseCurrency = baseCurrency
            )
            return if (recalculated.isSuccess) {
                val q = recalculated.getOrThrow()
                SelectedPaymentQuote(
                    transactionAmount = q.transactionAmount,
                    transactionCurrency = q.transactionCurrency,
                    baseAmount = q.baseAmount,
                    baseCurrency = q.baseCurrency,
                    fxRate = q.fxRate,
                    snapshot = q.snapshot
                )
            } else {
                Toast.makeText(requireContext(), "Falha na cotação: ${recalculated.exceptionOrNull()?.message}", Toast.LENGTH_SHORT).show()
                null
            }
        }

        // Configuração de Pagamento em Dinheiro
        binding.cardCash.setOnClickListener {
            val quote = resolveFinalQuote() ?: return@setOnClickListener
            onQuoteSelected?.invoke(PaymentType.CASH, quote)
            dismiss()
        }

        // Configuração de Pagamento Cartão/PlugPay
        binding.cardPlugPay.setOnClickListener {
            val quote = resolveFinalQuote() ?: return@setOnClickListener
            onQuoteSelected?.invoke(PaymentType.PLUG_PAY, quote)
            dismiss()
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_TOTAL = "arg_total"
        private const val ARG_BASE_CURRENCY = "arg_base_currency"

        fun newInstance(
            baseTotal: Double,
            baseCurrency: String? = null,
            onSelected: (PaymentType, SelectedPaymentQuote) -> Unit
        ): PaymentMethodSelectorBottomSheet {
            return PaymentMethodSelectorBottomSheet().apply {
                arguments = Bundle().apply {
                    putDouble(ARG_TOTAL, baseTotal)
                    if (!baseCurrency.isNullOrEmpty()) {
                        putString(ARG_BASE_CURRENCY, baseCurrency)
                    }
                }
                this.onQuoteSelected = onSelected
            }
        }
    }
}
