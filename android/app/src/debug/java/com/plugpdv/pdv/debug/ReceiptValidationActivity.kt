package com.plugpdv.pdv.debug

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.plugpdv.pdv.R
import com.plugpdv.pdv.hardware.printer.ReceiptData
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.LanguageManager
import com.plugpdv.pdv.utils.PrinterHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Debug-only, non-mutating physical receipt validation screen. */
class ReceiptValidationActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(32, 32, 32, 32)
        }
        status = TextView(this).apply { text = "Ready: no network/database/payment operations" }
        root.addView(status)
        listOf(
            "Español + BRL" to ("es" to ("BRL" to 10.28)),
            "Español + PYG" to ("es" to ("PYG" to 10000.0)),
            "Português + BRL" to ("pt" to ("BRL" to 10.28))
        ).forEach { (label, config) ->
            root.addView(Button(this).apply {
                text = label
                setOnClickListener { printSynthetic(config.first, config.second.first, config.second.second) }
            })
        }
        setContentView(root)
    }

    private fun printSynthetic(language: String, currency: String, amount: Double) {
        val previousLanguage = LanguageManager.getLanguage(this)
        LanguageManager.setLanguage(this, language)
        val localized = LanguageManager.updateResources(this, language)
        val formattedAmount = CurrencyManager.getInstance().formatExplicit(amount, currency)
        val now = Date()
        val date = SimpleDateFormat("dd/MM/yyyy", Locale(language)).format(now)
        val time = SimpleDateFormat("HH:mm:ss", Locale(language)).format(now)
        val data = ReceiptData().apply {
            title = localized.getString(R.string.print_payment_receipt_title)
            merchantName = "PlugPDV Teste"
            operatorName = "Operador Ñ"
            transactionId = "L10N-PRINT-TEST"
            this.date = date
            this.time = time
            this.amount = formattedAmount
            this.currency = currency
            customerName = "José Peña"
            paymentMethod = "CASH"
            status = "COMPLETED"
        }
        PrinterHelper.printRichReceipt(this, data)
        status.text = "PRINT_COMMAND_SUCCESS: $language + $currency ($formattedAmount)"
        LanguageManager.setLanguage(this, previousLanguage)
    }
}
