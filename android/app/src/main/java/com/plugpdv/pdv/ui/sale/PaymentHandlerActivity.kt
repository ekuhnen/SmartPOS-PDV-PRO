package com.plugpdv.pdv.ui.sale

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import android.content.Context
import android.content.pm.PackageManager
import com.plugpdv.pdv.ui.BaseActivity
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.PaymentResultStore
import dagger.hilt.android.AndroidEntryPoint
import org.json.JSONObject
import java.util.*

@AndroidEntryPoint
class PaymentHandlerActivity : BaseActivity() {

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_ORDER_ID = "order_id"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_TABLE_NUMBER = "table_number"
        const val EXTRA_IS_TABLE = "is_table"
        const val EXTRA_MERCHANT_ID = "merchant_id"
        const val EXTRA_AMOUNT_BRL = "amount_brl"
        const val EXTRA_AMOUNTS_JSON = "extra_amounts_json"

        private const val PAYMENT_APP_SCHEME = "plugpay"
        private const val PAYMENT_APP_HOST = "pay"
        private const val CALLBACK_SCHEME = "plugpdv"
        private const val CALLBACK_HOST = "payment_callback"
    }

    private var isWaitingForCallback = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val data = intent.data

        if (data != null && CALLBACK_SCHEME == data.scheme && CALLBACK_HOST == data.host) {
            handlePaymentCallback(data)
        } else {
            if (!isWaitingForCallback) {
                startPayment(intent)
            }
        }
    }

    private fun startPayment(intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: System.currentTimeMillis().toString()
        val orderId = intent.getStringExtra(EXTRA_ORDER_ID) ?: "0"
        val amountStr = intent.getStringExtra(EXTRA_AMOUNT)
        val description = intent.getStringExtra(EXTRA_DESCRIPTION) ?: "Payment"
        val tableNumber = intent.getIntExtra(EXTRA_TABLE_NUMBER, -1)

        val currencyCode = CurrencyManager.getInstance().selectedCurrency.takeIf { it.isNotEmpty() } ?: "BRL"

        var amount = amountStr?.toDoubleOrNull() ?: 0.0
        val isNoFractionCurrency = currencyCode.equals("PYG", ignoreCase = true) || currencyCode.equals("ARS", ignoreCase = true)
        
        if (isNoFractionCurrency) {
            amount = Math.ceil(amount)
        }

        val formattedAmount = if (isNoFractionCurrency) {
            String.format(Locale.US, "%.0f", amount)
        } else {
            String.format(Locale.US, "%.2f", amount)
        }

        val merchantId = intent.getStringExtra(EXTRA_MERCHANT_ID).takeIf { !it.isNullOrEmpty() } ?: "merchant123"
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val email = prefs.getString(Constants.EMAIL, "") ?: ""
        val password = prefs.getString(Constants.PASSWORD, "") ?: ""

        var callbackUri = "$CALLBACK_SCHEME://$CALLBACK_HOST"
        if (tableNumber != -1) {
            callbackUri += "?table_number=$tableNumber"
        }

        val amountsJsonStr = intent.getStringExtra(EXTRA_AMOUNTS_JSON) ?: "{}"

        // Monta a URI conforme o protocolo do PixPlug (plugpay://pay)
        // Ref: deeplink_uri_reference.md seção 1
        val uriBuilder = Uri.Builder()
            .scheme(PAYMENT_APP_SCHEME)
            .authority(PAYMENT_APP_HOST)
            .appendQueryParameter("amount", formattedAmount)
            .appendQueryParameter("selected_currency", currencyCode)
            .appendQueryParameter("amounts", amountsJsonStr)
            .appendQueryParameter("request_id", requestId)
            .appendQueryParameter("callback_uri", callbackUri)

        if (email.isNotEmpty() && password.isNotEmpty()) {
            uriBuilder.appendQueryParameter("email", email)
            uriBuilder.appendQueryParameter("password", password)
        }

        val paymentUri = uriBuilder.build()

        val paymentIntent = Intent(Intent.ACTION_VIEW, paymentUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.br.plugpay") // Força o Android a procurar apenas neste pacote
        }

        try {
            startActivity(paymentIntent)
            isWaitingForCallback = true
        } catch (e: Exception) {
            Log.e("PaymentHandlerActivity", "Falha ao abrir app de pagamento: ", e)
            appNotFoundResult(e.message ?: "Erro desconhecido")
        }
    }

    private fun appNotFoundResult(errorDetail: String = "") {
        val msg = "Aplicativo de pagamento não encontrado. $errorDetail"
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        val result = Intent().apply {
            putExtra("status", "ERROR")
            putExtra("message", "Payment app not found: $errorDetail")
        }
        setResult(Activity.RESULT_CANCELED, result)
        finish()
    }

    private fun handlePaymentCallback(uri: Uri) {
        val status = uri.getQueryParameter("status") ?: "ERROR"
        val paymentId = uri.getQueryParameter("payment_id")
        val method = uri.getQueryParameter("method")
        val message = uri.getQueryParameter("message")
        val tableNum = uri.getQueryParameter("table_number")

        Log.d("PaymentHandlerActivity", "Callback recebido: status=$status, method=$method, paymentId=$paymentId")

        if (status.equals("APPROVED", ignoreCase = true)) {
            if (tableNum != null) {
                // Fluxo de mesa: vai para TableOrderActivity com auto-checkout
                val tableIntent = Intent(this, TableOrderActivity::class.java).apply {
                    putExtra("TABLE_NUMBER", tableNum.toInt())
                    putExtra("AUTO_CHECKOUT", true)
                    putExtra("payment_method", method)
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(tableIntent)
            } else {
                // Fluxo de venda direta: grava no PaymentResultStore e vai para CheckoutActivity
                PaymentResultStore.setResult(
                    PaymentResultStore.PaymentResult(
                        status = status,
                        paymentId = paymentId,
                        method = method,
                        message = message
                    )
                )
                val checkoutIntent = Intent(this, CheckoutActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(checkoutIntent)
            }
        } else {
            // Pagamento recusado ou erro
            Log.w("PaymentHandlerActivity", "Pagamento não aprovado: status=$status, message=$message")
            val checkoutIntent = Intent(this, CheckoutActivity::class.java).apply {
                putExtra("PAYMENT_FAILED", true)
                putExtra("PAYMENT_MESSAGE", message ?: "Pagamento não aprovado")
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            startActivity(checkoutIntent)
        }

        finish()
    }
}
