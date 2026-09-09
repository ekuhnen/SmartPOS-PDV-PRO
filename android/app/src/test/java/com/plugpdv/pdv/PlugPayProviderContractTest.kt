package com.plugpdv.pdv

import android.content.Intent
import com.plugpdv.pdv.payment.PaymentProviderRequest
import com.plugpdv.pdv.payment.PlugPayProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PlugPayProviderContractTest {

    private val provider = PlugPayProvider()

    @Test
    fun buildsExactLegacyPlugPayDeeplinkForBrl() {
        val intent = provider.buildIntent(
            PaymentProviderRequest(
                reference = "req-123",
                amountMinor = 1234L,
                currency = "BRL",
                callbackUri = "plugpdv://payment_callback?table_number=7&request_id=req-123",
                customerEmail = "caixa@example.com",
                quoteAmountsJson = "{\"BRL\":\"12.34\"}"
            )
        )

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(PlugPayProvider.PAYMENT_APP_PACKAGE, intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        val uri = requireNotNull(intent.data)
        assertEquals(PlugPayProvider.PAYMENT_APP_SCHEME, uri.scheme)
        assertEquals(PlugPayProvider.PAYMENT_APP_HOST, uri.host)
        assertEquals("12.34", uri.getQueryParameter("amount"))
        assertEquals("BRL", uri.getQueryParameter("selected_currency"))
        assertEquals("{\"BRL\":\"12.34\"}", uri.getQueryParameter("amounts"))
        assertEquals("req-123", uri.getQueryParameter("request_id"))
        assertEquals(
            "plugpdv://payment_callback?table_number=7&request_id=req-123",
            uri.getQueryParameter("callback_uri")
        )
        assertEquals("caixa@example.com", uri.getQueryParameter("email"))
    }

    @Test
    fun keepsZeroDecimalCurrencyProtocolShape() {
        val intent = provider.buildIntent(
            PaymentProviderRequest(
                reference = "req-pyg",
                amountMinor = 150000L,
                currency = "PYG",
                callbackUri = "plugpdv://payment_callback?request_id=req-pyg"
            )
        )

        val uri = requireNotNull(intent.data)
        assertEquals("150000", uri.getQueryParameter("amount"))
        assertEquals("PYG", uri.getQueryParameter("selected_currency"))
        assertEquals("{}", uri.getQueryParameter("amounts"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun requiresCallbackForExternalPlugPayFlow() {
        provider.buildIntent(
            PaymentProviderRequest(
                reference = "req-no-callback",
                amountMinor = 100L,
                currency = "BRL"
            )
        )
    }
}
