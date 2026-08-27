package com.plugpdv.pdv.ui.sale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.plugpdv.pdv.databinding.LayoutPaymentMethodSelectorBinding
import com.plugpdv.pdv.utils.CurrencyManager

class PaymentMethodSelectorBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutPaymentMethodSelectorBinding? = null
    private val binding get() = _binding!!

    private var totalAmount: Double = 0.0
    private var onMethodSelected: ((PaymentType, Double, String, Double) -> Unit)? = null

    enum class PaymentType {
        CASH, PLUG_PAY
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        totalAmount = arguments?.getDouble(ARG_TOTAL) ?: 0.0
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutPaymentMethodSelectorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val cm = CurrencyManager.getInstance()
        val localTotal = cm.convert(totalAmount)

        // Ajusta o prefixo para a moeda selecionada (ex: "Gs. ", "R$ ")
        binding.tilAmount.prefixText = "${cm.selectedCurrency} "
        
        val isNoDecimals = cm.selectedCurrency.equals("PYG", ignoreCase = true) || cm.selectedCurrency.equals("ARS", ignoreCase = true)
        val formattedLocal = if (isNoDecimals) {
            String.format("%.0f", Math.ceil(localTotal))
        } else if (localTotal % 1.0 == 0.0) {
            String.format("%.0f", localTotal)
        } else {
            String.format("%.2f", localTotal).replace(",", ".")
        }

        binding.etAmount.setText(formattedLocal)

        val isOffline = isNetworkOffline(requireContext())

        // Configuração de Pagamento em Dinheiro (Permitido Offline)
        binding.cardCash.setOnClickListener {
            val localAmountStr = binding.etAmount.text.toString().replace(",", ".")
            val localAmount = localAmountStr.toDoubleOrNull() ?: localTotal
            val amountBrl = cm.convertToBrl(localAmount)
            onMethodSelected?.invoke(PaymentType.CASH, localAmount, cm.selectedCurrency, amountBrl)
            dismiss()
        }

        // Configuração de Pagamento Cartão/PlugPay (Verifica se offline)
        val plugPayAllowedOffline = true
        if (isOffline && !plugPayAllowedOffline) {
            binding.cardPlugPay.isEnabled = false
            binding.cardPlugPay.alpha = 0.5f
        } else {
            binding.cardPlugPay.setOnClickListener {
                val localAmountStr = binding.etAmount.text.toString().replace(",", ".")
                val localAmount = localAmountStr.toDoubleOrNull() ?: localTotal
                val amountBrl = cm.convertToBrl(localAmount)
                onMethodSelected?.invoke(PaymentType.PLUG_PAY, localAmount, cm.selectedCurrency, amountBrl)
                dismiss()
            }
        }

        binding.btnCancel.setOnClickListener {
            dismiss()
        }
    }

    private fun isNetworkOffline(context: android.content.Context): Boolean {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val activeNetwork = cm?.activeNetworkInfo
        return activeNetwork == null || !activeNetwork.isConnected
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_TOTAL = "arg_total"

        fun newInstance(total: Double, onSelected: (PaymentType, Double, String, Double) -> Unit): PaymentMethodSelectorBottomSheet {
            return PaymentMethodSelectorBottomSheet().apply {
                arguments = Bundle().apply {
                    putDouble(ARG_TOTAL, total)
                }
                this.onMethodSelected = onSelected
            }
        }
    }
}
