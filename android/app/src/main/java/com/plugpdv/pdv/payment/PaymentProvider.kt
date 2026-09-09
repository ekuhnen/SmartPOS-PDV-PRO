package com.plugpdv.pdv.payment

import android.app.Activity
import com.plugpdv.pdv.utils.PaymentProviderType

/**
 * Provider-neutral payment request. Monetary truth stays as Long minor units.
 * Provider adapters are responsible only for translating this request to their
 * own protocol/SDK surface.
 */
data class PaymentProviderRequest(
    val reference: String,
    val amountMinor: Long,
    val currency: String,
    val description: String? = null,
    val orderId: String? = null,
    val callbackUri: String? = null,
    val customerEmail: String? = null,
    val quoteAmountsJson: String? = null
)

sealed class PaymentProviderStartResult {
    data object Started : PaymentProviderStartResult()

    data class Failed(
        val code: String,
        val message: String? = null,
        val cause: Throwable? = null
    ) : PaymentProviderStartResult()
}

/**
 * Execution boundary for payment integrations.
 *
 * The durable payment state machine remains outside provider implementations.
 * A provider may open another app (PlugPay) or execute an in-process SDK
 * (Cielo Tap) without changing the caller contract.
 */
interface PaymentProvider {
    val type: PaymentProviderType

    fun start(
        host: Activity,
        request: PaymentProviderRequest
    ): PaymentProviderStartResult
}
