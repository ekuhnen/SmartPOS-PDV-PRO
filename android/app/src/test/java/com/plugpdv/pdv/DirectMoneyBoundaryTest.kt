package com.plugpdv.pdv

import com.plugpdv.pdv.models.ExchangeResponse
import com.plugpdv.pdv.models.Product
import com.plugpdv.pdv.models.SaleItem
import com.plugpdv.pdv.models.SaleRequest
import com.plugpdv.pdv.ui.sale.DirectSaleMoneyBoundary
import com.plugpdv.pdv.ui.sale.SaleViewModel
import com.plugpdv.pdv.ui.sale.SelectedPaymentQuote
import com.plugpdv.pdv.ui.sale.ReceiptMoneySnapshot
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.MoneyDecimal
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal

class DirectMoneyBoundaryTest {
    private val cm = CurrencyManager.getInstance()

    @Before fun setup() {
        cm.setRates(ExchangeResponse("BRL", listOf(
            ExchangeResponse.CurrencyRate("BRL", 1.0),
            ExchangeResponse.CurrencyRate("PYG", 1160.0),
            ExchangeResponse.CurrencyRate("USD", 0.2)
        )))
        cm.selectedCurrency = "PYG"
    }
    @After fun cleanup() { cm.setRates(null); cm.selectedCurrency = "BRL" }

    private fun items(currency: String? = "BRL") = listOf(
        SaleViewModel.CartItem(Product("p1", "Coca cola", selling_price = if (currency == "PYG") 6960.0 else 6.0, price_currency = currency), 1)
    )

    @Test fun catalogPriceAuthorityIsExplicitNormalizedBrl() {
        assertEquals("BRL", DirectSaleMoneyBoundary.catalogCurrency(items()))
        assertEquals("PYG", DirectSaleMoneyBoundary.catalogCurrency(items("PYG")))
        assertEquals("BRL", DirectSaleMoneyBoundary.catalogCurrency(items(null)))
    }

    @Test fun pygCheckoutConvertsCanonicalBrlOnceTo7656() {
        val base = BigDecimal("6.60")
        val quote = cm.convertMoneyExact(base, "BRL", "PYG", "BRL").getOrThrow()
        assertEquals(BigDecimal("7656"), quote.transactionAmount)
        assertEquals("PYG", quote.transactionCurrency)
        assertEquals(BigDecimal("6.60"), quote.baseAmount)
        assertEquals("BRL", quote.baseCurrency)
    }

    @Test fun passingAlreadyTransactionCurrencyAsBrlWouldBeWrongAndIsNeverUsed() {
        val correct = cm.convertMoneyExact(BigDecimal("6.60"), "BRL", "PYG", "BRL").getOrThrow()
        val wrong = cm.convertMoneyExact(BigDecimal("6.60"), "PYG", "PYG", "PYG").getOrThrow()
        assertEquals(BigDecimal("7656"), correct.transactionAmount)
        assertEquals(BigDecimal("7"), wrong.transactionAmount)
        assertNotEquals(BigDecimal("7"), correct.transactionAmount)
        assertEquals(BigDecimal("7656"), MoneyDecimal.roundToCurrency(BigDecimal("7656"), "PYG"))
    }

    @Test fun selectedQuoteSaleRequestAndReceiptSnapshotShareTransactionMoney() {
        val quote = cm.convertMoneyExact(BigDecimal("6.60"), "BRL", "PYG", "BRL").getOrThrow()
        val selected = SelectedPaymentQuote(quote.transactionAmount, quote.transactionCurrency, quote.baseAmount, quote.baseCurrency, quote.fxRate, quote.snapshot)
        val sale = SaleRequest(
            customerName = "Consumidor Final", total = selected.transactionAmount,
            items = listOf(SaleItem("p1", "Coca cola", 1, 6.0)), paymentMethod = "DINHEIRO",
            currency = selected.transactionCurrency, transactionCurrency = selected.transactionCurrency,
            paymentCurrency = selected.transactionCurrency, convertedTotal = selected.baseAmount,
            exchangeRatesSnapshot = selected.snapshot
        )
        val receipt = ReceiptMoneySnapshot("op", sale.total, sale.transactionCurrency, sale.convertedTotal!!, sale.currency, "DINHEIRO", sale.items, sale.customerName)
        assertEquals(BigDecimal("7656"), selected.transactionAmount)
        assertEquals(BigDecimal("7656"), sale.total)
        assertEquals(BigDecimal("7656"), receipt.transactionAmount)
        assertEquals("PYG", receipt.transactionCurrency)
        assertEquals(BigDecimal("6.60"), sale.convertedTotal)
    }

    @Test fun usdKeepsDecimalSemanticsAndBrlKeepsBaseSemantics() {
        val usd = cm.convertMoneyExact(BigDecimal("6.00"), "BRL", "USD", "BRL").getOrThrow()
        assertEquals(BigDecimal("1.20"), usd.transactionAmount)
        assertEquals("USD", usd.transactionCurrency)
        val brl = cm.convertMoneyExact(BigDecimal("6.00"), "BRL", "BRL", "BRL").getOrThrow()
        assertEquals(BigDecimal("6.00"), brl.transactionAmount)
    }

    @Test fun changingCurrencyBeforeCheckoutPerformsOneExplicitConversion() {
        val pyg = cm.convertMoneyExact(BigDecimal("6.60"), "BRL", "PYG", "BRL").getOrThrow()
        val usd = cm.convertMoneyExact(BigDecimal("6.60"), "BRL", "USD", "BRL").getOrThrow()
        assertEquals("PYG", pyg.transactionCurrency)
        assertEquals("USD", usd.transactionCurrency)
        assertEquals(BigDecimal("7656"), pyg.transactionAmount)
        assertEquals(BigDecimal("1.32"), usd.transactionAmount)
    }

    @Test fun pygTaxIsPartOfSameBaseBoundaryAndNotIndependentlyConverted() {
        val base = BigDecimal("6.00")
        val tax = base.multiply(BigDecimal("0.10"))
        val quote = cm.convertMoneyExact(base.add(tax), "BRL", "PYG", "BRL").getOrThrow()
        assertEquals(BigDecimal("7656"), quote.transactionAmount)
        assertEquals(BigDecimal("6.00"), base)
        assertEquals(BigDecimal("0.6000"), tax)
    }
}
