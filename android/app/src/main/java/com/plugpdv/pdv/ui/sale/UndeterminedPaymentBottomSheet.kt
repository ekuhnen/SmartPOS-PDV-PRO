package com.plugpdv.pdv.ui.sale

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.plugpdv.pdv.database.PaymentAttemptEntity
import com.plugpdv.pdv.databinding.LayoutUndeterminedPaymentBinding
import com.plugpdv.pdv.utils.CurrencyManager

class UndeterminedPaymentBottomSheet(
    private val attempt: PaymentAttemptEntity,
    private val onRetryCheck: (PaymentAttemptEntity) -> Unit,
    private val onMarkPending: (PaymentAttemptEntity) -> Unit,
    private val onViewReceipt: ((PaymentAttemptEntity) -> Unit)? = null
) : BottomSheetDialogFragment() {

    private var _binding: LayoutUndeterminedPaymentBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutUndeterminedPaymentBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val amountDouble = attempt.amount / 100.0
        val formattedAmount = CurrencyManager.getInstance().formatExplicit(amountDouble, attempt.currency)

        binding.tvAttemptDetails.text = buildString {
            append("Valor: $formattedAmount\n")
            append("Referência: ${attempt.reference}\n")
            if (attempt.tableNumber != null && attempt.tableNumber != -1) {
                append("Mesa: ${attempt.tableNumber}\n")
            }
            if (!attempt.statusMessage.isNullOrEmpty()) {
                append("Detalhes: ${attempt.statusMessage}")
            }
        }

        // Ação 1: Consultar de novo
        binding.btnRetryCheck.setOnClickListener {
            dismiss()
            onRetryCheck(attempt)
        }

        // Ação 2: Registrar como pendente e resolver depois
        binding.btnMarkPending.setOnClickListener {
            dismiss()
            onMarkPending(attempt)
        }

        // Ação 3: Ver comprovante do app de pagamento
        binding.btnViewReceipt.setOnClickListener {
            if (onViewReceipt != null) {
                onViewReceipt.invoke(attempt)
            } else {
                openPaymentAppReceipt(attempt)
            }
            dismiss()
        }
    }

    private fun openPaymentAppReceipt(attempt: PaymentAttemptEntity) {
        try {
            val receiptUri = Uri.parse("plugpay://receipt?payment_id=${attempt.paymentAppPaymentId ?: attempt.reference}&request_id=${attempt.reference}")
            val intent = Intent(Intent.ACTION_VIEW, receiptUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage("com.br.plugpay")
            }
            startActivity(intent)
        } catch (e: Exception) {
            // Se não abrir por URI direta, tenta abrir o aplicativo de pagamento
            try {
                val launchIntent = requireContext().packageManager.getLaunchIntentForPackage("com.br.plugpay")
                launchIntent?.let { startActivity(it) }
            } catch (e2: Exception) {
                e2.printStackTrace()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val TAG = "UndeterminedPaymentBottomSheet"

        fun newInstance(
            attempt: PaymentAttemptEntity,
            onRetryCheck: (PaymentAttemptEntity) -> Unit,
            onMarkPending: (PaymentAttemptEntity) -> Unit,
            onViewReceipt: ((PaymentAttemptEntity) -> Unit)? = null
        ): UndeterminedPaymentBottomSheet {
            return UndeterminedPaymentBottomSheet(attempt, onRetryCheck, onMarkPending, onViewReceipt)
        }
    }
}
