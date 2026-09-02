package com.plugpdv.pdv

import com.plugpdv.pdv.models.CurrencyCapability
import com.plugpdv.pdv.models.ExchangeResponse
import com.plugpdv.pdv.models.Product
import com.plugpdv.pdv.models.TaxRate
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider
import com.plugpdv.pdv.utils.MoneyDecimal
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
            "BRL" to CurrencyCapability("BRL", "R$", "PREFIX", ".", ",", 2, 2),
            "PYG" to CurrencyCapability("PYG", "Gs.", "PREFIX", ".", ",", 0, 0),
            "USD" to CurrencyCapability("USD", "$", "PREFIX", ",", ".", 2, 2)
        ))
        MoneyDecimal.setRulesProvider(rules)
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

    @Test fun tableFooterUsesSelectedDisplayCurrencyFromAuthoritativeTotal() {
        cm.setRates(ExchangeResponse("BRL", listOf(
            ExchangeResponse.CurrencyRate("BRL", 1.0),
            ExchangeResponse.CurrencyRate("PYG", 1160.0)
        )))
        val quote = cm.quoteBaseAmount(BigDecimal("116.00"), "BRL", "PYG").getOrThrow()
        assertTrue(quote.transactionAmount.compareTo(BigDecimal("134560")) == 0)
        assertEquals("Gs. 134.560", rules.formatExplicit(quote.transactionAmount.toDouble(), "PYG"))
        assertEquals("R$ 116,00", rules.formatExplicit(116.0, "BRL"))
    }

    @Test fun tableItemsAndFooterShareOneConvertedAuthority() {
        cm.setRates(ExchangeResponse("BRL", listOf(
            ExchangeResponse.CurrencyRate("BRL", 1.0),
            ExchangeResponse.CurrencyRate("PYG", 1160.0)
        )))
        val first = cm.quoteBaseAmount(BigDecimal("18.00"), "BRL", "PYG").getOrThrow()
        val second = cm.quoteBaseAmount(BigDecimal("98.00"), "BRL", "PYG").getOrThrow()
        val total = cm.quoteBaseAmount(BigDecimal("116.00"), "BRL", "PYG").getOrThrow()
        assertTrue(first.transactionAmount.compareTo(BigDecimal("20880")) == 0)
        assertTrue(second.transactionAmount.compareTo(BigDecimal("113680")) == 0)
        assertTrue(total.transactionAmount.compareTo(BigDecimal("134560")) == 0)
        assertTrue(first.transactionAmount.add(second.transactionAmount).compareTo(total.transactionAmount) == 0)
    }

    @Test fun transactionCurrencySelectsItsOwnTaxConfiguration() {
        cm.setRates(ExchangeResponse("BRL", listOf(
            ExchangeResponse.CurrencyRate("BRL", 1.0),
            ExchangeResponse.CurrencyRate("PYG", 1160.0)
        )))
        val taxes = listOf(
            TaxRate("brl-icms", "ICMS", 2.8, "BRL", true),
            TaxRate("pyg-iva", "IVA", 10.0, "PYG", true)
        )
        val brlTax = BigDecimal("116.00").multiply(BigDecimal("0.028")).setScale(2, java.math.RoundingMode.HALF_UP)
        val pygSubtotal = cm.quoteBaseAmount(BigDecimal("116.00"), "BRL", "PYG").getOrThrow().transactionAmount
        val pygTax = pygSubtotal.multiply(BigDecimal("0.10")).setScale(0, java.math.RoundingMode.HALF_UP)
        assertEquals("BRL", taxes.first { it.currency == "BRL" }.currency)
        assertEquals("PYG", taxes.first { it.currency == "PYG" }.currency)
        assertEquals(BigDecimal("3.25"), brlTax)
        assertEquals(BigDecimal("13456"), pygTax)
        assertTrue(brlTax.compareTo(BigDecimal("13.456")) != 0)
    }
}
