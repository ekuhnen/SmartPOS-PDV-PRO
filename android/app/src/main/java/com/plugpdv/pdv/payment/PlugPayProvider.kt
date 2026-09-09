package com.plugpdv.pdv.payment

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.plugpdv.pdv.utils.MoneyDecimal
import com.plugpdv.pdv.utils.PaymentProviderType
import javax.inject.Inject

/**
 * Adapter for the existing PlugPay deeplink contract.
 *
 * Keep all PlugPay protocol details here so PaymentHandlerActivity no longer
 * needs to know scheme, host, package name or query parameter names once the
 * adapter is wired in PAYMENT-02B.
 */
class PlugPayProvider @Inject constructor() : PaymentProvider {

    override val type: PaymentProviderType = PaymentProviderType.PLUGPAY

    override fun start(
        host: Activity,
        request: PaymentProviderRequest
    ): PaymentProviderStartResult {
        return try {
            host.startActivity(buildIntent(request))
            PaymentProviderStartResult.Started
        } catch (error: Exception) {
            PaymentProviderStartResult.Failed(
                code = "FAILED_TO_START",
                message = error.message,
                cause = error
            )
        }
    }

    internal fun buildIntent(request: PaymentProviderRequest): Intent {
        require(request.reference.isNotBlank()) { "payment reference is required" }
        require(request.currency.isNotBlank()) { "payment currency is required" }
        val callback = requireNotNull(request.callbackUri?.takeIf { it.isNotBlank() }) {
            "PlugPay callbackUri is required"
        }

        val amount = MoneyDecimal.fromMinorUnits(request.amountMinor, request.currency)
        val protocolAmount = MoneyDecimal.toProtocolAmount(amount, request.currency)

        val uriBuilder = Uri.Builder()
            .scheme(PAYMENT_APP_SCHEME)
            .authority(PAYMENT_APP_HOST)
            .appendQueryParameter("amount", protocolAmount)
            .appendQueryParameter("selected_currency", request.currency)
            .appendQueryParameter("amounts", request.quoteAmountsJson ?: "{}")
            .appendQueryParameter("request_id", request.reference)
            .appendQueryParameter("callback_uri", callback)

        request.customerEmail
            ?.takeIf { it.isNotEmpty() }
            ?.let { uriBuilder.appendQueryParameter("email", it) }

        return Intent(Intent.ACTION_VIEW, uriBuilder.build()).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            setPackage(PAYMENT_APP_PACKAGE)
        }
    }

    companion object {
        internal const val PAYMENT_APP_SCHEME = "plugpay"
        internal const val PAYMENT_APP_HOST = "pay"
        internal const val PAYMENT_APP_PACKAGE = "com.br.plugpay"
    }
}
