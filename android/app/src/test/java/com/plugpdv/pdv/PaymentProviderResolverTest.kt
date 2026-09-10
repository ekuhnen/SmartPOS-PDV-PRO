package com.plugpdv.pdv

import com.plugpdv.pdv.payment.PaymentProviderResolution
import com.plugpdv.pdv.payment.PaymentProviderResolver
import com.plugpdv.pdv.utils.PaymentProviderPolicy
import com.plugpdv.pdv.utils.PaymentProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentProviderResolverTest {

    private val plugPayOnlyApk = setOf(PaymentProviderType.PLUGPAY)
    private val futureBothProviders = setOf(PaymentProviderType.PLUGPAY, PaymentProviderType.CIELO_TAP)

    @Test
    fun legacyPolicySelectsPlugPay() {
        val policy = PaymentProviderPolicy(
            enabledProviders = listOf(PaymentProviderType.PLUGPAY),
            defaultProvider = PaymentProviderType.PLUGPAY,
            allowOperatorSelection = false,
            legacyFallback = true
        )

        assertEquals(
            PaymentProviderResolution.Selected(PaymentProviderType.PLUGPAY),
            PaymentProviderResolver.resolve(policy, plugPayOnlyApk)
        )
    }

    @Test
    fun explicitEmptyPolicyFailsClosed() {
        val policy = PaymentProviderPolicy(emptyList(), null, false, false)

        val result = PaymentProviderResolver.resolve(policy, plugPayOnlyApk)
        assertTrue(result is PaymentProviderResolution.Blocked)
        assertEquals("PAYMENT_PROVIDER_NOT_AUTHORIZED", (result as PaymentProviderResolution.Blocked).code)
    }

    @Test
    fun cieloOnlyDoesNotFallbackToPlugPay() {
        val policy = PaymentProviderPolicy(
            enabledProviders = listOf(PaymentProviderType.CIELO_TAP),
            defaultProvider = PaymentProviderType.CIELO_TAP,
            allowOperatorSelection = false,
            legacyFallback = false
        )

        val result = PaymentProviderResolver.resolve(policy, plugPayOnlyApk)
        assertTrue(result is PaymentProviderResolution.Blocked)
        assertEquals("PAYMENT_PROVIDER_UNAVAILABLE", (result as PaymentProviderResolution.Blocked).code)
    }

    @Test
    fun bothAllowedWithSelectionFallsBackToOnlyExecutableProvider() {
        val policy = PaymentProviderPolicy(
            enabledProviders = listOf(PaymentProviderType.PLUGPAY, PaymentProviderType.CIELO_TAP),
            defaultProvider = PaymentProviderType.CIELO_TAP,
            allowOperatorSelection = true,
            legacyFallback = false
        )

        assertEquals(
            PaymentProviderResolution.Selected(PaymentProviderType.PLUGPAY),
            PaymentProviderResolver.resolve(policy, plugPayOnlyApk)
        )
    }

    @Test
    fun disabledSelectionDoesNotReplaceUnavailableDefault() {
        val policy = PaymentProviderPolicy(
            enabledProviders = listOf(PaymentProviderType.PLUGPAY, PaymentProviderType.CIELO_TAP),
            defaultProvider = PaymentProviderType.CIELO_TAP,
            allowOperatorSelection = false,
            legacyFallback = false
        )

        val result = PaymentProviderResolver.resolve(policy, plugPayOnlyApk)
        assertTrue(result is PaymentProviderResolution.Blocked)
        assertEquals("PAYMENT_DEFAULT_PROVIDER_UNAVAILABLE", (result as PaymentProviderResolution.Blocked).code)
    }

    @Test
    fun futureTwoExecutableProvidersRequireSelectionWhenAllowed() {
        val policy = PaymentProviderPolicy(
            enabledProviders = listOf(PaymentProviderType.PLUGPAY, PaymentProviderType.CIELO_TAP),
            defaultProvider = PaymentProviderType.CIELO_TAP,
            allowOperatorSelection = true,
            legacyFallback = false
        )

        val result = PaymentProviderResolver.resolve(policy, futureBothProviders)
        assertTrue(result is PaymentProviderResolution.SelectionRequired)
        result as PaymentProviderResolution.SelectionRequired
        assertEquals(listOf(PaymentProviderType.PLUGPAY, PaymentProviderType.CIELO_TAP), result.providers)
        assertEquals(PaymentProviderType.CIELO_TAP, result.defaultProvider)
    }

    @Test
    fun explicitRequestedProviderMustBeAuthorizedAndImplemented() {
        val policy = PaymentProviderPolicy(
            enabledProviders = listOf(PaymentProviderType.PLUGPAY, PaymentProviderType.CIELO_TAP),
            defaultProvider = PaymentProviderType.PLUGPAY,
            allowOperatorSelection = true,
            legacyFallback = false
        )

        val result = PaymentProviderResolver.resolve(
            policy,
            plugPayOnlyApk,
            requestedProvider = PaymentProviderType.CIELO_TAP
        )
        assertTrue(result is PaymentProviderResolution.Blocked)
        assertEquals("PAYMENT_PROVIDER_NOT_IMPLEMENTED", (result as PaymentProviderResolution.Blocked).code)
    }
}
