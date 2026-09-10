package com.plugpdv.pdv.payment

import android.app.Activity
import com.plugpdv.pdv.utils.PaymentProviderPolicy
import com.plugpdv.pdv.utils.PaymentProviderType
import javax.inject.Inject

/**
 * Single execution entry point for payment providers.
 *
 * PlugPay is currently the only executable provider. Resolution is already
 * policy-aware so Cielo can be added without changing financial orchestration.
 */
class PaymentCoordinator @Inject constructor(
    private val plugPayProvider: PlugPayProvider
) {

    fun resolve(
        policy: PaymentProviderPolicy,
        requestedProvider: PaymentProviderType? = null
    ): PaymentProviderResolution {
        return PaymentProviderResolver.resolve(
            policy = policy,
            implementedProviders = implementedProviders(),
            requestedProvider = requestedProvider
        )
    }

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
