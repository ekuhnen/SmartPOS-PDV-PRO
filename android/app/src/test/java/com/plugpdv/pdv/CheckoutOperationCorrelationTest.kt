package com.plugpdv.pdv

import com.plugpdv.pdv.ui.sale.CheckoutOperationCorrelation
import org.junit.Assert.assertEquals
import org.junit.Test

class CheckoutOperationCorrelationTest {
    @Test
    fun providerRequestIdWinsAfterActivityRecreation() {
        assertEquals("op-provider", CheckoutOperationCorrelation.resolve("op-provider", null))
    }

    @Test
    fun pendingOperationIsFallbackWhenCallbackOmitsRequestId() {
        assertEquals("op-local", CheckoutOperationCorrelation.resolve(null, "op-local"))
    }
}
