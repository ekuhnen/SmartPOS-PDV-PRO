package com.plugpdv.pdv.payment

import com.plugpdv.pdv.utils.PaymentProviderPolicy
import com.plugpdv.pdv.utils.PaymentProviderType

sealed class PaymentProviderResolution {
    data class Selected(val provider: PaymentProviderType) : PaymentProviderResolution()

    data class SelectionRequired(
        val providers: List<PaymentProviderType>,
        val defaultProvider: PaymentProviderType?
    ) : PaymentProviderResolution()

    data class Blocked(
        val code: String,
        val message: String
    ) : PaymentProviderResolution()
}

/**
 * Composes backend authorization with providers executable by this APK/device.
 *
 * The resolver never invents authorization and never silently substitutes an
 * unavailable server default when operator selection is disabled.
 */
object PaymentProviderResolver {

    fun resolve(
        policy: PaymentProviderPolicy,
        implementedProviders: Set<PaymentProviderType>,
        requestedProvider: PaymentProviderType? = null
    ): PaymentProviderResolution {
        val authorized = policy.enabledProviders.distinct()
        val executable = authorized.filter { it in implementedProviders }

        if (requestedProvider != null) {
            if (requestedProvider !in authorized) {
                return PaymentProviderResolution.Blocked(
                    code = "PAYMENT_PROVIDER_NOT_AUTHORIZED",
                    message = "Provider ${requestedProvider.name} is not authorized by terminal capabilities"
                )
            }
            if (requestedProvider !in implementedProviders) {
                return PaymentProviderResolution.Blocked(
                    code = "PAYMENT_PROVIDER_NOT_IMPLEMENTED",
                    message = "Provider ${requestedProvider.name} is not executable by this APK"
                )
            }
            return PaymentProviderResolution.Selected(requestedProvider)
        }

        if (authorized.isEmpty()) {
            return PaymentProviderResolution.Blocked(
                code = "PAYMENT_PROVIDER_NOT_AUTHORIZED",
                message = "No payment provider is authorized for this transaction"
            )
        }

        if (executable.isEmpty()) {
            return PaymentProviderResolution.Blocked(
                code = "PAYMENT_PROVIDER_UNAVAILABLE",
                message = "No authorized payment provider is executable by this APK"
            )
        }

        val executableDefault = policy.defaultProvider?.takeIf { it in executable }

        if (policy.allowOperatorSelection) {
            return if (executable.size == 1) {
                PaymentProviderResolution.Selected(executable.single())
            } else {
                PaymentProviderResolution.SelectionRequired(
                    providers = executable,
                    defaultProvider = executableDefault
                )
            }
        }

        if (policy.defaultProvider != null) {
            return executableDefault?.let { PaymentProviderResolution.Selected(it) }
                ?: PaymentProviderResolution.Blocked(
                    code = "PAYMENT_DEFAULT_PROVIDER_UNAVAILABLE",
                    message = "Configured default provider ${policy.defaultProvider.name} is not executable"
                )
        }

        return if (executable.size == 1) {
            PaymentProviderResolution.Selected(executable.single())
        } else {
            PaymentProviderResolution.Blocked(
                code = "PAYMENT_DEFAULT_PROVIDER_REQUIRED",
                message = "Multiple executable providers require an explicit default or operator selection"
            )
        }
    }
}
