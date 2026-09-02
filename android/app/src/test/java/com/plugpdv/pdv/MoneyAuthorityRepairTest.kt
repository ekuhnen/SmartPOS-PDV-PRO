package com.plugpdv.pdv

import com.plugpdv.pdv.models.CurrencyCapability
import com.plugpdv.pdv.models.ExchangeResponse
import com.plugpdv.pdv.models.Product
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class MoneyAuthorityRepairTest {
    private val cm = CurrencyManager.getInstance()
    private val rules = DefaultCurrencyRulesProvider()

    @Before fun setup() {
        cm.setRates(ExchangeResponse("BRL", listOf(
            ExchangeResponse.CurrencyRate("BRL", 1.0),
            ExchangeResponse.CurrencyRate("PYG", 1170.0),
            ExchangeResponse.CurrencyRate("USD", 0.2)
        )))
        rules.setCapabilities(mapOf(
            "BRL" to CurrencyCapability("BRL", "R$", "PREFIX", ".", ",", 2),
            "PYG" to CurrencyCapability("PYG", "Gs.", "PREFIX", ".", ",", 0),
            "USD" to CurrencyCapability("USD", "$", "PREFIX", ",", ".", 2)
        ))
    }

    @Test fun baseQuoteUsesExplicitDirectionAndScreenshotFixture() {
        val quote = cm.quoteBaseAmount(BigDecimal("65.00"), "BRL", "PYG").getOrThrow()
        assertEquals("65.00", quote.baseAmount.toPlainString())
        assertEquals("76050", quote.transactionAmount.toPlainString())
        assertEquals("Gs. 76.050", rules.formatExplicit(quote.transactionAmount.toDouble(), "PYG"))
    }

    @Test fun currentRateFixtureUsesSuppliedRate() {
        cm.setRates(ExchangeResponse("BRL", listOf(
            ExchangeResponse.CurrencyRate("BRL", 1.0),
            ExchangeResponse.CurrencyRate("PYG", 1160.0)
        )))
        val quote = cm.quoteBaseAmount(BigDecimal("65.00"), "BRL", "PYG").getOrThrow()
        assertEquals("75400", quote.transactionAmount.toPlainString())
    }

    @Test fun pygScaleIsZeroDecimalAndRoundTripStartsAtAuthority() {
        val quote = cm.quoteBaseAmount(BigDecimal("45.00"), "BRL", "PYG").getOrThrow()
        assertEquals("52650", quote.transactionAmount.toPlainString())
        assertEquals("Gs. 52.650", rules.formatExplicit(quote.transactionAmount.toDouble(), "PYG"))

        val back = cm.quoteBaseAmount(BigDecimal("65.00"), "BRL", "PYG").getOrThrow()
        val again = cm.quoteBaseAmount(back.baseAmount, back.baseCurrency, back.transactionCurrency).getOrThrow()
        assertTrue(back.transactionAmount.compareTo(again.transactionAmount) == 0)
    }

    @Test fun productAmountAndCurrencyRemainOneSemanticValue() {
        val product = Product(id = "hamburguesa", selling_price = 45.00, price_currency = "BRL")
        assertEquals(45.00, product.selling_price!!, 0.0)
        assertEquals("BRL", product.price_currency)
    }
}
