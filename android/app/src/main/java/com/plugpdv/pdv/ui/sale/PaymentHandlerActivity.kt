package com.plugpdv.pdv.ui.sale

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.plugpdv.pdv.database.PaymentAttemptDao
import com.plugpdv.pdv.database.PaymentAttemptEntity
import com.plugpdv.pdv.ui.BaseActivity
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.PaymentResultStore
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.*
import javax.inject.Inject

@AndroidEntryPoint
class PaymentHandlerActivity : BaseActivity() {

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_IDEMPOTENCY_KEY = "idempotency_key"
        const val EXTRA_ORDER_ID = "order_id"
        const val EXTRA_AMOUNT = "amount"
        const val EXTRA_DESCRIPTION = "description"
        const val EXTRA_TABLE_NUMBER = "table_number"
        const val EXTRA_TABLE_ID = "table_id"
        const val EXTRA_IS_TABLE = "is_table"
        const val EXTRA_MERCHANT_ID = "merchant_id"
        const val EXTRA_AMOUNT_BRL = "amount_brl"
        const val EXTRA_AMOUNTS_JSON = "extra_amounts_json"
        const val EXTRA_CURRENCY = "extra_currency"

        private const val PAYMENT_APP_SCHEME = "plugpay"
        private const val PAYMENT_APP_HOST = "pay"
        private const val CALLBACK_SCHEME = "plugpdv"
        private const val CALLBACK_HOST = "payment_callback"
        private const val TAG = "PaymentHandlerActivity"
    }

    @Inject
    lateinit var paymentAttemptDao: PaymentAttemptDao

    @Inject
    lateinit var outboxDao: com.plugpdv.pdv.database.OutboxDao

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
            val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
            val idempotencyKey = intent.getStringExtra(EXTRA_IDEMPOTENCY_KEY)
            val tableNum = intent.getIntExtra(EXTRA_TABLE_NUMBER, -1)
            val tableId = intent.getStringExtra(EXTRA_TABLE_ID)

            val isModernFlow = !requestId.isNullOrBlank() || !idempotencyKey.isNullOrBlank()
            val extraCurrency = intent.getStringExtra(EXTRA_CURRENCY)

            if (isModernFlow && extraCurrency.isNullOrBlank()) {
                Log.e(TAG, "PAYMENT_CURRENCY_REQUIRED: Fluxo moderno requer EXTRA_CURRENCY explícito.")
                deliverFailedResult("PAYMENT_CURRENCY_REQUIRED", "Moeda da transação é obrigatória", if (tableNum != -1) tableNum.toString() else null, tableId)
                return
            }

            lifecycleScope.launch {
                val existingAttempt = if (!requestId.isNullOrEmpty()) {
                    withContext(Dispatchers.IO) { paymentAttemptDao.getByReference(requestId) }
                } else null

                if (existingAttempt != null) {
                    when (existingAttempt.status) {
                        PaymentAttemptEntity.STATUS_PREPARED -> {
                            handlePreparedAttempt(intent, existingAttempt, tableNum, tableId)
                        }
                        PaymentAttemptEntity.STATUS_APPROVED -> {
                            Log.d(TAG, "Tentativa K=$requestId já APROVADA no Room. Recuperando resultado sem reabrir PlugPay.")
                            deliverApprovedResult(
                                requestId = existingAttempt.reference,
                                paymentId = existingAttempt.paymentAppPaymentId,
                                method = existingAttempt.paymentMethod,
                                message = existingAttempt.statusMessage,
                                tableNum = if (tableNum != -1) tableNum.toString() else null,
                                tableId = tableId
                            )
                        }
                        PaymentAttemptEntity.STATUS_PENDING -> {
                            Log.d(TAG, "Tentativa K=$requestId em PENDING pós recriação. Bloqueando reabertura automática.")
                            Toast.makeText(this@PaymentHandlerActivity, "Pagamento aguardando confirmação...", Toast.LENGTH_SHORT).show()
                        }
                        PaymentAttemptEntity.STATUS_UNKNOWN -> {
                            Log.d(TAG, "Tentativa K=$requestId em UNKNOWN pós recriação. Exibindo conciliação.")
                            showUndeterminedPaymentDialog(existingAttempt, if (tableNum != -1) tableNum.toString() else null)
                        }
                        PaymentAttemptEntity.STATUS_CANCELLED, PaymentAttemptEntity.STATUS_REJECTED, "FAILED_TO_START" -> {
                            Log.d(TAG, "Tentativa K=$requestId em estado terminal (${existingAttempt.status}).")
                            deliverFailedResult(existingAttempt.status, existingAttempt.statusMessage, if (tableNum != -1) tableNum.toString() else null, tableId)
                        }
                        else -> {
                            Log.w(TAG, "Tentativa K=$requestId em estado não esperado (${existingAttempt.status}).")
                            showUndeterminedPaymentDialog(existingAttempt, if (tableNum != -1) tableNum.toString() else null)
                        }
                    }
                } else {
                    startPayment(intent)
                }
            }
        }
    }

    private fun handlePreparedAttempt(
        intent: Intent,
        existingAttempt: PaymentAttemptEntity,
        tableNumber: Int,
        tableId: String?
    ) {
        val extraCurrency = intent.getStringExtra(EXTRA_CURRENCY)
        if (extraCurrency.isNullOrBlank()) {
            Log.e(TAG, "PAYMENT_CURRENCY_REQUIRED: Intent extra currency is null or blank for prepared attempt")
            deliverFailedResult("PAYMENT_CURRENCY_REQUIRED", "Moeda da transação é obrigatória", if (tableNumber != -1) tableNumber.toString() else null, tableId)
            return
        }
        if (!extraCurrency.equals(existingAttempt.currency, ignoreCase = true)) {
            Log.e(TAG, "PAYMENT_QUOTE_MISMATCH: Intent currency $extraCurrency != attempt currency ${existingAttempt.currency}")
            deliverFailedResult("PAYMENT_QUOTE_MISMATCH", "Discrepância na moeda da cotação", if (tableNumber != -1) tableNumber.toString() else null, tableId)
            return
        }

        val amountStr = intent.getStringExtra(EXTRA_AMOUNT)
        val amountBigDecimal = amountStr?.let { runCatching { java.math.BigDecimal(it) }.getOrNull() } ?: java.math.BigDecimal.ZERO
        val calculatedMinorUnits = com.plugpdv.pdv.utils.MoneyDecimal.toMinorUnits(amountBigDecimal, existingAttempt.currency)

        if (calculatedMinorUnits != existingAttempt.amount) {
            Log.e(TAG, "PAYMENT_AMOUNT_MISMATCH: Calculated minor units $calculatedMinorUnits != attempt amount ${existingAttempt.amount}")
            deliverFailedResult("PAYMENT_AMOUNT_MISMATCH", "Discrepância no valor do pagamento", if (tableNumber != -1) tableNumber.toString() else null, tableId)
            return
        }

        val displayDecimals = com.plugpdv.pdv.utils.MoneyDecimal.getDisplayDecimals(existingAttempt.currency)
        val roundedAmount = com.plugpdv.pdv.utils.MoneyDecimal.roundToCurrency(amountBigDecimal, existingAttempt.currency)
        val formattedAmount = if (displayDecimals == 0) {
            roundedAmount.toBigInteger().toString()
        } else {
            roundedAmount.setScale(displayDecimals, java.math.RoundingMode.HALF_UP).toPlainString()
        }

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val email = prefs.getString(Constants.EMAIL, "") ?: ""
        val password = prefs.getString(Constants.PASSWORD, "") ?: ""

        var callbackUri = "$CALLBACK_SCHEME://$CALLBACK_HOST"
        if (tableNumber != -1) {
            callbackUri += "?table_number=$tableNumber"
            if (!tableId.isNullOrEmpty()) {
                callbackUri += "&table_id=$tableId"
            }
        }

        val amountsJsonStr = intent.getStringExtra(EXTRA_AMOUNTS_JSON) ?: "{}"

        val uriBuilder = Uri.Builder()
            .scheme(PAYMENT_APP_SCHEME)
            .authority(PAYMENT_APP_HOST)
            .appendQueryParameter("amount", formattedAmount)
            .appendQueryParameter("selected_currency", existingAttempt.currency)
            .appendQueryParameter("amounts", amountsJsonStr)
            .appendQueryParameter("request_id", existingAttempt.reference)
            .appendQueryParameter("callback_uri", callbackUri)

        if (email.isNotEmpty() && password.isNotEmpty()) {
            uriBuilder.appendQueryParameter("email", email)
            uriBuilder.appendQueryParameter("password", password)
        }

        val paymentUri = uriBuilder.build()
        val paymentIntent = Intent(Intent.ACTION_VIEW, paymentUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage("com.br.plugpay")
        }

        lifecycleScope.launch {
            val now = System.currentTimeMillis()
            val updatedAttempt = existingAttempt.copy(
                status = PaymentAttemptEntity.STATUS_PENDING,
                startedAt = now
            )
            withContext(Dispatchers.IO) {
                paymentAttemptDao.update(updatedAttempt)
            }
            Log.i(TAG, "Attempt K=${existingAttempt.reference} promovida PREPARED -> PENDING no Room antes de abrir PlugPay")

            try {
                startActivity(paymentIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao abrir app de pagamento para PREPARED attempt: ", e)
                withContext(Dispatchers.IO) {
                    paymentAttemptDao.update(
                        updatedAttempt.copy(
                            status = "FAILED_TO_START",
                            statusMessage = e.message
                        )
                    )
                    outboxDao.markAsFailedWithKey(
                        id = existingAttempt.reference,
                        error = "FAILED_TO_START",
                        messageKey = "FAILED_TO_START",
                        isRetriable = false
                    )
                }
                appNotFoundResult(e.message ?: "Erro desconhecido")
            }
        }
    }

    private fun startPayment(intent: Intent) {
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID) ?: UUID.randomUUID().toString()
        val idempotencyKey = intent.getStringExtra(EXTRA_IDEMPOTENCY_KEY) ?: UUID.randomUUID().toString()
        val nonce = UUID.randomUUID().toString()
        val orderId = intent.getStringExtra(EXTRA_ORDER_ID) ?: "0"
        val amountStr = intent.getStringExtra(EXTRA_AMOUNT)
        val description = intent.getStringExtra(EXTRA_DESCRIPTION) ?: "Payment"
        val tableNumber = intent.getIntExtra(EXTRA_TABLE_NUMBER, -1)

        val tableId = intent.getStringExtra(EXTRA_TABLE_ID)

        val currencyCode = intent.getStringExtra(EXTRA_CURRENCY)
            ?: CurrencyManager.getInstance().selectedCurrency.takeIf { it.isNotEmpty() }
            ?: "BRL"
        val amountBigDecimal = amountStr?.let { runCatching { java.math.BigDecimal(it) }.getOrNull() } ?: java.math.BigDecimal.ZERO
        val roundedAmount = com.plugpdv.pdv.utils.MoneyDecimal.roundToCurrency(amountBigDecimal, currencyCode)
        val displayDecimals = com.plugpdv.pdv.utils.MoneyDecimal.getDisplayDecimals(currencyCode)

        val formattedAmount = if (displayDecimals == 0) {
            roundedAmount.toBigInteger().toString()
        } else {
            roundedAmount.setScale(displayDecimals, java.math.RoundingMode.HALF_UP).toPlainString()
        }

        // Converte para unidade mínima da moeda (Long) para invariante de dinheiro
        val minimalUnitAmount = com.plugpdv.pdv.utils.MoneyDecimal.toMinorUnits(roundedAmount, currencyCode)

        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val email = prefs.getString(Constants.EMAIL, "") ?: ""
        val password = prefs.getString(Constants.PASSWORD, "") ?: ""

        var callbackUri = "$CALLBACK_SCHEME://$CALLBACK_HOST"
        if (tableNumber != -1) {
            callbackUri += "?table_number=$tableNumber"
            if (!tableId.isNullOrEmpty()) {
                callbackUri += "&table_id=$tableId"
            }
        }

        val amountsJsonStr = intent.getStringExtra(EXTRA_AMOUNTS_JSON) ?: "{}"

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
            setPackage("com.br.plugpay")
        }

        lifecycleScope.launch {
            // INVARIANTE 9: PERSISTA EM ROOM ANTES DE DISPARAR O DEEPLINK
            val attemptEntity = PaymentAttemptEntity(
                reference = requestId,
                idempotencyKey = idempotencyKey,
                nonce = nonce,
                amount = minimalUnitAmount,
                currency = currencyCode,
                status = PaymentAttemptEntity.STATUS_PENDING,
                startedAt = System.currentTimeMillis(),
                tableNumber = if (tableNumber != -1) tableNumber else null,
                orderId = orderId,
                description = description
            )
            withContext(Dispatchers.IO) {
                paymentAttemptDao.insert(attemptEntity)
            }
            Log.d(TAG, "Tentativa de pagamento persistida no Room antes do deeplink. Ref: $requestId")

            try {
                startActivity(paymentIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Falha ao abrir app de pagamento: ", e)
                withContext(Dispatchers.IO) {
                    paymentAttemptDao.update(
                        attemptEntity.copy(
                            status = "FAILED_TO_START",
                            statusMessage = e.message
                        )
                    )
                    outboxDao.markAsFailedWithKey(
                        id = requestId,
                        error = "FAILED_TO_START",
                        messageKey = "FAILED_TO_START",
                        isRetriable = false
                    )
                }
                appNotFoundResult(e.message ?: "Erro desconhecido")
            }
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
        val rawStatus = uri.getQueryParameter("status") ?: "UNKNOWN"
        val paymentId = uri.getQueryParameter("payment_id")
        val method = uri.getQueryParameter("method")
        val message = uri.getQueryParameter("message")
        val tableNum = uri.getQueryParameter("table_number")
        val tableId = uri.getQueryParameter("table_id")
        val requestId = uri.getQueryParameter("request_id")

        Log.d(TAG, "Callback recebido: status=$rawStatus, paymentId=$paymentId, requestId=$requestId, tableNum=$tableNum, tableId=$tableId")

        lifecycleScope.launch {
            // Recuperar tentativa a partir do Room
            val existingAttempt = withContext(Dispatchers.IO) {
                if (!requestId.isNullOrEmpty()) {
                    paymentAttemptDao.getByReference(requestId)
                } else if (tableNum != null) {
                    paymentAttemptDao.getLatestPendingForTable(tableNum.toIntOrNull() ?: -1)
                } else null
            }

            val isApproved = rawStatus.equals("APPROVED", ignoreCase = true)
            val isCancelled = rawStatus.equals("CANCELLED", ignoreCase = true) || rawStatus.equals("CANCELED", ignoreCase = true)
            val isRejected = rawStatus.equals("REJECTED", ignoreCase = true) || rawStatus.equals("DECLINED", ignoreCase = true)
            val isUndetermined = !isApproved && !isCancelled && !isRejected

            val normalizedStatus = when {
                isApproved -> "APPROVED"
                isCancelled -> "CANCELLED"
                isRejected -> "REJECTED"
                else -> "UNKNOWN" // INVARIANTE 7: unknown nunca é apresentado como recusa
            }

            // REGRA DE PRECEDÊNCIA: APPROVED nunca regride para PENDING/UNKNOWN/REJECTED/CANCELLED
            if (existingAttempt?.status == "APPROVED" && !isApproved) {
                Log.w(TAG, "Tentativa K=$requestId já está APPROVED. Ignorando regressão para $normalizedStatus.")
                deliverApprovedResult(
                    requestId = existingAttempt.reference,
                    paymentId = existingAttempt.paymentAppPaymentId,
                    method = existingAttempt.paymentMethod,
                    message = existingAttempt.statusMessage,
                    tableNum = tableNum,
                    tableId = tableId
                )
                return@launch
            }

            if (existingAttempt != null) {
                val updatedAttempt = existingAttempt.copy(
                    status = normalizedStatus,
                    completedAt = System.currentTimeMillis(),
                    paymentMethod = method ?: existingAttempt.paymentMethod,
                    paymentAppPaymentId = paymentId ?: existingAttempt.paymentAppPaymentId,
                    statusMessage = message ?: existingAttempt.statusMessage,
                    rawCallbackUri = uri.toString()
                )
                withContext(Dispatchers.IO) {
                    paymentAttemptDao.update(updatedAttempt)
                    if (isCancelled || isRejected) {
                        outboxDao.markAsFailedWithKey(
                            id = updatedAttempt.reference,
                            error = normalizedStatus,
                            messageKey = "CANCELLED_PAYMENT",
                            isRetriable = false
                        )
                        Log.i(TAG, "Outbox K=${updatedAttempt.reference} terminalizada como CANCELLED_PAYMENT ($normalizedStatus). Mesa continua aberta.")
                    }
                }
                Log.d(TAG, "Tentativa de pagamento atualizada no Room: ref=${updatedAttempt.reference}, status=$normalizedStatus")
            }

            if (isApproved) {
                val effectiveRequestId = requestId ?: existingAttempt?.reference
                PaymentResultStore.setResult(
                    PaymentResultStore.PaymentResult(
                        status = "APPROVED",
                        paymentId = paymentId,
                        method = method,
                        message = message,
                        requestId = effectiveRequestId
                    )
                )
                deliverApprovedResult(effectiveRequestId, paymentId, method, message, tableNum, tableId)
            } else if (isUndetermined) {
                // Pagamento não determinado: abre tela com opções seguras para não cobrar duas vezes
                showUndeterminedPaymentDialog(
                    attempt = existingAttempt ?: PaymentAttemptEntity(
                        reference = requestId ?: "UNKNOWN",
                        idempotencyKey = requestId ?: "UNKNOWN",
                        nonce = "",
                        amount = 0L,
                        currency = CurrencyManager.getInstance().selectedCurrency,
                        status = "UNKNOWN",
                        startedAt = System.currentTimeMillis(),
                        tableNumber = tableNum?.toIntOrNull(),
                        statusMessage = message
                    ),
                    tableNum = tableNum
                )
            } else {
                // Cancelado ou recusado explicitamente pelo app de pagamento
                deliverFailedResult(rawStatus, message, tableNum, tableId)
            }
        }
    }

    private fun deliverApprovedResult(requestId: String?, paymentId: String?, method: String?, message: String?, tableNum: String?, tableId: String? = null) {
        val resultIntent = Intent().apply {
            putExtra("status", "APPROVED")
            putExtra("payment_id", paymentId)
            putExtra("request_id", requestId)
            putExtra("method", method)
            putExtra("message", message)
        }

        if (!isTaskRoot) {
            setResult(Activity.RESULT_OK, resultIntent)
            finish()
        } else {
            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val token = prefs.getString(Constants.TOKEN, "") ?: ""
            if (tableNum != null) {
                val tableIntent = Intent(this, TableOrderActivity::class.java).apply {
                    if (!tableId.isNullOrEmpty()) {
                        putExtra("TABLE_ID", tableId)
                    }
                    putExtra("TABLE_NUMBER", tableNum.toInt())
                    putExtra("AUTO_CHECKOUT", true)
                    putExtra("payment_method", method)
                    putExtra("ACCESS_TOKEN", token)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(tableIntent)
            } else {
                val checkoutIntent = Intent(this, CheckoutActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(checkoutIntent)
            }
            finish()
        }
    }

    private fun deliverFailedResult(status: String, message: String?, tableNum: String?, tableId: String? = null) {
        val resultIntent = Intent().apply {
            putExtra("status", status)
            putExtra("message", message)
        }

        if (!isTaskRoot) {
            setResult(Activity.RESULT_CANCELED, resultIntent)
            finish()
        } else {
            val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            val token = prefs.getString(Constants.TOKEN, "") ?: ""
            if (tableNum != null) {
                val tableIntent = Intent(this, TableOrderActivity::class.java).apply {
                    if (!tableId.isNullOrEmpty()) {
                        putExtra("TABLE_ID", tableId)
                    }
                    putExtra("TABLE_NUMBER", tableNum.toInt())
                    putExtra("AUTO_CHECKOUT", true)
                    putExtra("ACCESS_TOKEN", token)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(tableIntent)
            } else {
                val checkoutIntent = Intent(this, CheckoutActivity::class.java).apply {
                    putExtra("PAYMENT_FAILED", true)
                    putExtra("PAYMENT_MESSAGE", message ?: "Pagamento não finalizado")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                startActivity(checkoutIntent)
            }
            finish()
        }
    }

    private fun showUndeterminedPaymentDialog(attempt: PaymentAttemptEntity, tableNum: String?) {
        if (isFinishing || isDestroyed) return
        val existing = supportFragmentManager.findFragmentByTag(UndeterminedPaymentBottomSheet.TAG)
        if (existing != null) {
            return
        }
        val sheet = UndeterminedPaymentBottomSheet.newInstance(
            attempt = attempt,
            onRetryCheck = {
                // Re-dispara verificação / consulta de status
                Toast.makeText(this, "Consultando status no servidor...", Toast.LENGTH_SHORT).show()
                // Mantém a tela/mesa aberta
            },
            onMarkPending = {
                Toast.makeText(this, "Pagamento registrado como pendente. A mesa permanece aberta.", Toast.LENGTH_LONG).show()
                deliverFailedResult("PENDING_VERIFICATION", "Pagamento pendente de confirmação", tableNum)
            }
        )
        sheet.show(supportFragmentManager, UndeterminedPaymentBottomSheet.TAG)
    }
}
