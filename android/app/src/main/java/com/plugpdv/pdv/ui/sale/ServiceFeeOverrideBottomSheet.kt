package com.plugpdv.pdv.ui.sale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.RadioButton
import android.widget.RadioGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.plugpdv.pdv.R
import com.plugpdv.pdv.utils.CurrencyManager

class ServiceFeeOverrideBottomSheet : BottomSheetDialogFragment() {

    private var baseAmount: Double = 0.0
    private var onApply: ((String, Double, (Boolean) -> Unit) -> Unit)? = null
    private var convertManualValueToBrl: Boolean = true
    private var comandaCurrency: String? = null
    private var defaultPercent: Double? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.layout_service_fee_override, container, false)
        
        val rgFeeKind = view.findViewById<RadioGroup>(R.id.rgFeeKind)
        val rbFixed = view.findViewById<RadioButton>(R.id.rbFixed)
        val rbManualPercent = view.findViewById<RadioButton>(R.id.rbManualPercent)
        val rbManualValue = view.findViewById<RadioButton>(R.id.rbManualValue)
        val rbWaived = view.findViewById<RadioButton>(R.id.rbWaived)
        val etManualValue = view.findViewById<EditText>(R.id.etManualValue)
        val btnApply = view.findViewById<Button>(R.id.btnApply)
        val progress = view.findViewById<android.widget.ProgressBar>(R.id.progressApply)

        defaultPercent?.let {
            rbFixed.text = getString(R.string.service_fee_default_with_percent, formatPercent(it))
        }

        rgFeeKind.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbManualPercent || checkedId == R.id.rbManualValue) {
                etManualValue.visibility = View.VISIBLE
                etManualValue.hint = if (checkedId == R.id.rbManualValue) {
                    getString(R.string.service_fee_amount_hint, comandaCurrency ?: CurrencyManager.getInstance().selectedCurrency)
                } else {
                    getString(R.string.value_or_percentage)
                }
            } else {
                etManualValue.visibility = View.GONE
            }
        }

        btnApply.setOnClickListener {
            val valueStr = etManualValue.text.toString()
            val localValue = valueStr.replace(",", ".").toDoubleOrNull() ?: 0.0
            val kind = when (rgFeeKind.checkedRadioButtonId) {
                R.id.rbFixed -> "fixed"
                R.id.rbManualPercent -> "manual_percent"
                R.id.rbManualValue -> "manual_value"
                R.id.rbWaived -> "waived"
                else -> "fixed"
            }

            // O ViewModel e o Carrinho trabalham apenas com BRL base.
            // Precisamos converter o valor digitado (que está na moeda local do checkout, ex: Gs) de volta para BRL,
            // MAS APENAS se o valor digitado for financeiro. Se for porcentagem, mantemos o número puro.
            val value = if (kind == "manual_value" && convertManualValueToBrl) {
                CurrencyManager.getInstance().convertToBrl(localValue)
            } else {
                localValue
            }

            btnApply.isEnabled = false
            progress.visibility = View.VISIBLE
            onApply?.invoke(kind, value) { success ->
                if (success) dismiss()
                else {
                    btnApply.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }

        return view
    }

    private fun formatPercent(value: Double): String =
        java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

    companion object {
        fun newInstance(
            baseAmount: Double,
            convertManualValueToBrl: Boolean = true,
            comandaCurrency: String? = null,
            defaultPercent: Double? = null,
            onApply: (String, Double, (Boolean) -> Unit) -> Unit
        ): ServiceFeeOverrideBottomSheet {
            val fragment = ServiceFeeOverrideBottomSheet()
            fragment.baseAmount = baseAmount
            fragment.onApply = onApply
            fragment.convertManualValueToBrl = convertManualValueToBrl
            fragment.comandaCurrency = comandaCurrency
            fragment.defaultPercent = defaultPercent
            return fragment
        }
    }
}
