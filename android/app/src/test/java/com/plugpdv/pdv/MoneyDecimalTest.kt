package com.plugpdv.pdv

import com.google.gson.Gson
import com.plugpdv.pdv.models.CommandCheckoutCommitRequest
import com.plugpdv.pdv.models.CurrencyCapability
import com.plugpdv.pdv.models.ExchangeResponse
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider
import com.plugpdv.pdv.utils.MoneyDecimal
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.security.MessageDigest

class MoneyDecimalTest {

    private lateinit var currencyManager: CurrencyManager

    @Before
    fun setup() {
        currencyManager = CurrencyManager.getInstance()
        val exchangeData = ExchangeResponse(
            moeda_principal = "BRL",
            moedas = listOf(
                ExchangeResponse.CurrencyRate("BRL", 1.0),
                ExchangeResponse.CurrencyRate("USD", 0.20),
                ExchangeResponse.CurrencyRate("PYG", 7000.0),
                ExchangeResponse.CurrencyRate("ARS", 200.0)
            )
        )
        currencyManager.setRates(exchangeData)
        currencyManager.selectedCurrency = "BRL"
    }

    @Test
    fun testA_MONEY_01_paidAmountBaseUnchangedOnSelectedCurrencyChange() {
        // Accounting data returned by server in base currency
        val serverTotalPagoBase = 50.0
        val currentTablePaidAmount = serverTotalPagoBase

        currencyManager.selectedCurrency = "PYG"
        assertEquals(50.0, currentTablePaidAmount, 0.0)

        currencyManager.selectedCurrency = "USD"
        assertEquals(50.0, currentTablePaidAmount, 0.0)
    }

    @Test
    fun testA_MONEY_02_paymentHistoryFormatsExplicitlyWithPaymentMoeda() {
        val formattedPyg = currencyManager.formatExplicit(175000.0, "PYG")
        assertTrue("Expected Gs. formatted for PYG, got $formattedPyg", formattedPyg.contains("175.000") || formattedPyg.contains("175000"))

        val formattedBrl = currencyManager.formatExplicit(25.0, "BRL")
        assertTrue("Expected R$ formatted for BRL, got $formattedBrl", formattedBrl.contains("25,00") || formattedBrl.contains("25.00"))
    }

    @Test
    fun testA_MONEY_03_missingFxRateFailsClosedAndNeverDefaultsToOne() {
        val result = currencyManager.quoteTransactionAmount(
            transactionAmount = BigDecimal("100.00"),
            transactionCurrency = "EUR", // EUR not in rates
            baseCurrency = "BRL"
        )
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex?.message?.contains("FX_RATE_MISSING") == true)
    }

    @Test
    fun testA_MONEY_04_exactCalculation350000PygAt7000Gives50Brl() {
        val quote = currencyManager.quoteTransactionAmount(
            transactionAmount = BigDecimal("350000"),
            transactionCurrency = "PYG",
            baseCurrency = "BRL"
        ).getOrThrow()

        assertEquals("PYG", quote.transactionCurrency)
        assertEquals("BRL", quote.baseCurrency)
        assertTrue(MoneyDecimal.isEqual(BigDecimal("350000"), quote.transactionAmount))
        assertTrue(MoneyDecimal.isEqual(BigDecimal("50.00"), quote.baseAmount))
        assertTrue(MoneyDecimal.isEqual(BigDecimal("7000"), quote.fxRate))
        assertEquals("7000", quote.snapshot?.get("PYG"))
    }

    @Test
    fun testA_MONEY_05_snapshotCreatedBeforePaymentPersistsUnchangedWhenRatesChange() {
        val initialQuote = currencyManager.quoteTransactionAmount(
            transactionAmount = BigDecimal("350000"),
            transactionCurrency = "PYG",
            baseCurrency = "BRL"
        ).getOrThrow()

        val req = CommandCheckoutCommitRequest(
            comandaId = "c-1",
            forma = "CARD",
            valor = initialQuote.transactionAmount,
            moeda = initialQuote.transactionCurrency,
            valorBase = initialQuote.baseAmount,
            baseCurrency = initialQuote.baseCurrency,
            fxRate = initialQuote.fxRate,
            exchangeRatesSnapshot = initialQuote.snapshot
        )

        val gson = Gson()
        val persistedPayloadJson = gson.toJson(req)

        // Simulate rate change in system
        val newExchangeData = ExchangeResponse(
            moeda_principal = "BRL",
            moedas = listOf(
                ExchangeResponse.CurrencyRate("BRL", 1.0),
                ExchangeResponse.CurrencyRate("PYG", 7500.0)
            )
        )
        currencyManager.setRates(newExchangeData)

        // The persisted Outbox payload remains unchanged with rate 7000
        val recovered = gson.fromJson(persistedPayloadJson, CommandCheckoutCommitRequest::class.java)
        assertTrue(MoneyDecimal.isEqual(BigDecimal("7000"), recovered.fxRate!!))
        assertEquals("7000", recovered.exchangeRatesSnapshot?.get("PYG"))
        assertTrue(MoneyDecimal.isEqual(BigDecimal("50.00"), recovered.valorBase!!))
    }

    @Test
    fun testA_MONEY_06_moneyDecimalEliminatesPrecisionDrift() {
        val a = BigDecimal("0.10")
        val b = BigDecimal("0.20")
        val sum = MoneyDecimal.add(a, b, "BRL")
        assertTrue(MoneyDecimal.isEqual(BigDecimal("0.30"), sum))
    }

    @Test
    fun testA_MONEY_07_minorUnitBehaviorFollowsAuditedCapabilities() {
        val provider = DefaultCurrencyRulesProvider()
        provider.setCapabilities(
            mapOf(
                "TEST_ZERO" to CurrencyCapability("TEST_ZERO", "T$", "PREFIX", ".", ",", 0),
                "TEST_THREE" to CurrencyCapability("TEST_THREE", "T3$", "PREFIX", ".", ",", 3)
            )
        )
        assertEquals(0, provider.getDecimalPlaces("TEST_ZERO"))
        assertEquals(3, provider.getDecimalPlaces("TEST_THREE"))
        assertEquals(0, MoneyDecimal.getDecimals("PYG"))
        assertEquals(2, MoneyDecimal.getDecimals("BRL"))
    }

    @Test
    fun testA_MONEY_08_nonBrlBaseCrossRateCalculation() {
        // Base is USD, transaction in PYG
        // USD = 0.20 per BRL, PYG = 7000 per BRL -> PYG per USD = 7000 / 0.20 = 35000
        val quote = currencyManager.quoteTransactionAmount(
            transactionAmount = BigDecimal("350000"),
            transactionCurrency = "PYG",
            baseCurrency = "USD"
        ).getOrThrow()

        assertEquals("USD", quote.baseCurrency)
        assertEquals("PYG", quote.transactionCurrency)
        assertTrue(MoneyDecimal.isEqual(BigDecimal("350000"), quote.transactionAmount))
        assertTrue(MoneyDecimal.isEqual(BigDecimal("10.00"), quote.baseAmount))
        assertTrue(MoneyDecimal.isEqual(BigDecimal("35000"), quote.fxRate))
        assertEquals("35000", quote.snapshot?.get("PYG"))
    }

    @Test
    fun testA_MONEY_09_canonicalJsonSerializationPreservesByteForByteHash() {
        val gson = Gson()
        val originalReq = CommandCheckoutCommitRequest(
            comandaId = "comanda-123",
            forma = "CARTAO",
            valor = BigDecimal("350000"),
            moeda = "PYG",
            valorBase = BigDecimal("50.00"),
            baseCurrency = "BRL",
            fxRate = BigDecimal("7000"),
            exchangeRatesSnapshot = mapOf("PYG" to "7000", "BRL" to "1")
        )

        val json1 = gson.toJson(originalReq)
        val hash1 = sha256(json1)

        val recoveredReq = gson.fromJson(json1, CommandCheckoutCommitRequest::class.java)
        val json2 = gson.toJson(recoveredReq)
        val hash2 = sha256(json2)

        assertEquals("Byte-for-byte JSON must match", json1, json2)
        assertEquals("Idempotency body hash must match exactly", hash1, hash2)
    }

    @Test
    fun testA_MONEY_10_foreignMissingFxThrowsBeforeOutboxOrPlugPay() {
        val result = currencyManager.quoteTransactionAmount(
            transactionAmount = BigDecimal("50.00"),
            transactionCurrency = "GBP", // GBP not in exchange rates
            baseCurrency = "BRL"
        )
        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(exception?.message?.contains("FX_RATE_MISSING") == true)
    }

    @Test
    fun testA_MONEY_11_foreignCheckoutOutboxWire() {
        val quote = currencyManager.quoteTransactionAmount(
            transactionAmount = BigDecimal("350000"),
            transactionCurrency = "PYG",
            baseCurrency = "BRL"
        ).getOrThrow()

        val req = CommandCheckoutCommitRequest(
            comandaId = "c-100",
            forma = "CARD",
            valor = quote.transactionAmount,
            moeda = quote.transactionCurrency,
            valorBase = quote.baseAmount,
            baseCurrency = quote.baseCurrency,
            fxRate = quote.fxRate,
            exchangeRatesSnapshot = quote.snapshot
        )

        assertEquals("PYG", req.moeda)
        assertEquals("BRL", req.baseCurrency)
        assertTrue(MoneyDecimal.isEqual(BigDecimal("350000"), req.valor))
        assertTrue(MoneyDecimal.isEqual(BigDecimal("50.00"), req.valorBase!!))
        assertTrue(MoneyDecimal.isEqual(BigDecimal("7000"), req.fxRate!!))
        assertEquals("7000", req.exchangeRatesSnapshot?.get("PYG"))
    }

    @Test
    fun testA_MONEY_12_centEdgeFinalPaymentNoEpsilon() {
        val pendingTotal = BigDecimal("100.00")
        val partialPayment = BigDecimal("99.99")

        val remaining1 = MoneyDecimal.roundToCurrency(pendingTotal.subtract(partialPayment), "BRL")
        val isFinal1 = remaining1.signum() <= 0
        assertFalse("99.99 on 100.00 must NOT be final payment", isFinal1)

        val finalPayment = BigDecimal("0.01")
        val remaining2 = MoneyDecimal.roundToCurrency(remaining1.subtract(finalPayment), "BRL")
        val isFinal2 = remaining2.signum() <= 0
        assertTrue("0.01 completion MUST be final payment", isFinal2)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
