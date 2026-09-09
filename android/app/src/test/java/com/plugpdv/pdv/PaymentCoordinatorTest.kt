package com.plugpdv.pdv

import com.plugpdv.pdv.payment.PaymentCoordinator
import com.plugpdv.pdv.payment.PlugPayProvider
import com.plugpdv.pdv.utils.PaymentProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class PaymentCoordinatorTest {

    private val plugPayProvider = PlugPayProvider()
    private val coordinator = PaymentCoordinator(plugPayProvider)

    @Test
    fun payment02ARegistersOnlyPlugPayExecution() {
        assertEquals(setOf(PaymentProviderType.PLUGPAY), coordinator.implementedProviders())
        assertSame(plugPayProvider, coordinator.providerFor(PaymentProviderType.PLUGPAY))
    }

    @Test
    fun cieloIsNotSilentlyMappedToPlugPayBeforeSdkIntegration() {
        assertNull(coordinator.providerFor(PaymentProviderType.CIELO_TAP))
    }
}
