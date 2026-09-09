package com.plugpdv.pdv.payment

import android.app.Activity
import com.plugpdv.pdv.utils.PaymentProviderType
import javax.inject.Inject

/**
 * Single execution entry point for payment providers.
 *
 * PAYMENT-02A intentionally registers only PlugPay. Cielo Tap will be added
 * after the legacy PlugPay path is proven unchanged behind this boundary.
 */
class PaymentCoordinator @Inject constructor(
    private val plugPayProvider: PlugPayProvider
) {

    fun start(
        providerType: PaymentProviderType,
        host: Activity,
        request: PaymentProviderRequest
    ): PaymentProviderStartResult {
        val provider = providerFor(providerType)
            ?: return PaymentProviderStartResult.Failed(
                code = "PAYMENT_PROVIDER_NOT_IMPLEMENTED",
                message = "Provider ${providerType.name} is not implemented in this APK"
            )

        return provider.start(host, request)
    }

    fun implementedProviders(): Set<PaymentProviderType> = setOf(PaymentProviderType.PLUGPAY)

    internal fun providerFor(providerType: PaymentProviderType): PaymentProvider? {
        return when (providerType) {
            PaymentProviderType.PLUGPAY -> plugPayProvider
            PaymentProviderType.CIELO_TAP -> null
        }
    }
}
