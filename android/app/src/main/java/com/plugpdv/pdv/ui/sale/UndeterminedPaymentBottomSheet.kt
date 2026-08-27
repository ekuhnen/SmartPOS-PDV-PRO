package com.plugpdv.pdv.ui.sale

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.gson.Gson
import com.plugpdv.pdv.database.PaymentAttemptEntity
import com.plugpdv.pdv.databinding.LayoutUndeterminedPaymentBinding
import com.plugpdv.pdv.utils.CurrencyManager

class UndeterminedPaymentBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutUndeterminedPaymentBinding? = null
    private val binding get() = _binding!!

    private var attempt: PaymentAttemptEntity? = null
    var onRetryCheck: ((PaymentAttemptEntity) -> Unit)? = null
    var onMarkPending: ((PaymentAttemptEntity) -> Unit)? = null
    var onViewReceipt: ((PaymentAttemptEntity) -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val json = arguments?.getString(ARG_ATTEMPT_JSON)
        if (!json.isNullOrEmpty()) {
            attempt = Gson().fromJson(json, PaymentAttemptEntity::class.java)
        }
    }

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
        val currentAttempt = attempt ?: return

        val amountDouble = currentAttempt.amount / 100.0
        val formattedAmount = CurrencyManager.getInstance().formatExplicit(amountDouble, currentAttempt.currency)

        binding.tvAttemptDetails.text = buildString {
            append("Valor: $formattedAmount\n")
            append("Referência: ${currentAttempt.reference}\n")
            if (currentAttempt.tableNumber != null && currentAttempt.tableNumber != -1) {
                append("Mesa: ${currentAttempt.tableNumber}\n")
            }
            if (!currentAttempt.statusMessage.isNullOrEmpty()) {
                append("Detalhes: ${currentAttempt.statusMessage}")
            }
        }

        // Ação 1: Consultar de novo
        binding.btnRetryCheck.setOnClickListener {
            dismiss()
            onRetryCheck?.invoke(currentAttempt)
        }

        // Ação 2: Registrar como pendente e resolver depois
        binding.btnMarkPending.setOnClickListener {
            dismiss()
            onMarkPending?.invoke(currentAttempt)
        }

        // Ação 3: Ver comprovante do app de pagamento
        binding.btnViewReceipt.setOnClickListener {
            if (onViewReceipt != null) {
                onViewReceipt?.invoke(currentAttempt)
            } else {
                openPaymentAppReceipt(currentAttempt)
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
        private const val ARG_ATTEMPT_JSON = "arg_attempt_json"

        fun newInstance(
            attempt: PaymentAttemptEntity,
            onRetryCheck: ((PaymentAttemptEntity) -> Unit)? = null,
            onMarkPending: ((PaymentAttemptEntity) -> Unit)? = null,
            onViewReceipt: ((PaymentAttemptEntity) -> Unit)? = null
        ): UndeterminedPaymentBottomSheet {
            val sheet = UndeterminedPaymentBottomSheet()
            sheet.arguments = Bundle().apply {
                putString(ARG_ATTEMPT_JSON, Gson().toJson(attempt))
            }
            sheet.attempt = attempt
            sheet.onRetryCheck = onRetryCheck
            sheet.onMarkPending = onMarkPending
            sheet.onViewReceipt = onViewReceipt
            return sheet
        }
    }
}
