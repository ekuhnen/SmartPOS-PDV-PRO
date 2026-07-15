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
    private var onApply: ((String, Double) -> Unit)? = null

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

        rgFeeKind.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbManualPercent || checkedId == R.id.rbManualValue) {
                etManualValue.visibility = View.VISIBLE
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
            val value = if (kind == "manual_value") {
                CurrencyManager.getInstance().convertToBrl(localValue)
            } else {
                localValue
            }
            
            onApply?.invoke(kind, value)
            dismiss()
        }

        return view
    }

    companion object {
        fun newInstance(baseAmount: Double, onApply: (String, Double) -> Unit): ServiceFeeOverrideBottomSheet {
            val fragment = ServiceFeeOverrideBottomSheet()
            fragment.baseAmount = baseAmount
            fragment.onApply = onApply
            return fragment
        }
    }
}
