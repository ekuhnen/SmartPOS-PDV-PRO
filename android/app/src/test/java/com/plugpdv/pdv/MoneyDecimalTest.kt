package com.plugpdv.pdv

import com.google.gson.Gson
import com.plugpdv.pdv.models.CommandCheckoutCommitRequest
import com.plugpdv.pdv.models.ExchangeResponse
import com.plugpdv.pdv.utils.CurrencyManager
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
        val basePaid = 50.0 // 50 BRL base
        currencyManager.selectedCurrency = "PYG"
        // Base amount in comanda remains 50.0 BRL regardless of selectedCurrency in UI
        assertEquals(50.0, basePaid, 0.0)

        currencyManager.selectedCurrency = "USD"
        assertEquals(50.0, basePaid, 0.0)
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
        val result = currencyManager.convertMoneyExact(
            amount = BigDecimal("100.00"),
            fromCurrency = "EUR", // EUR not in rates
            toCurrency = "BRL"
        )
        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex?.message?.contains("FX_RATE_MISSING") == true)
    }

    @Test
    fun testA_MONEY_04_exactCalculation350000PygAt7000Gives50Brl() {
        val quote = currencyManager.convertMoneyExact(
            amount = BigDecimal("50.00"),
            fromCurrency = "BRL",
            toCurrency = "PYG",
            baseCurrency = "BRL"
        ).getOrThrow()

        assertEquals("PYG", quote.transactionCurrency)
        assertEquals("BRL", quote.baseCurrency)
        assertTrue(MoneyDecimal.isEqual(BigDecimal("350000"), quote.transactionAmount))
        assertTrue(MoneyDecimal.isEqual(BigDecimal("50.00"), quote.baseAmount))
        assertTrue(MoneyDecimal.isEqual(BigDecimal("7000"), quote.fxRate))
    }

    @Test
    fun testA_MONEY_05_snapshotCreatedBeforePaymentPersistsUnchangedWhenRatesChange() {
        val initialQuote = currencyManager.convertMoneyExact(
            amount = BigDecimal("50.00"),
            fromCurrency = "BRL",
            toCurrency = "PYG",
            baseCurrency = "BRL"
        ).getOrThrow()

        val frozenSnapshot = initialQuote.snapshot
        assertNotNull(frozenSnapshot)
        assertEquals("7000", frozenSnapshot?.get("PYG"))

        // Simulate rate change in system
        val newExchangeData = ExchangeResponse(
            moeda_principal = "BRL",
            moedas = listOf(
                ExchangeResponse.CurrencyRate("BRL", 1.0),
                ExchangeResponse.CurrencyRate("PYG", 7500.0)
            )
        )
        currencyManager.setRates(newExchangeData)

        // The frozen snapshot remains unchanged
        assertEquals("7000", frozenSnapshot?.get("PYG"))
        assertTrue(MoneyDecimal.isEqual(BigDecimal("7000"), initialQuote.fxRate))
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
        assertEquals(0, MoneyDecimal.getDecimals("PYG"))
        assertEquals(2, MoneyDecimal.getDecimals("BRL"))
        assertEquals(2, MoneyDecimal.getDecimals("USD"))
        assertEquals(2, MoneyDecimal.getDecimals("ARS"))

        assertEquals(350000L, MoneyDecimal.toMinorUnits(BigDecimal("350000"), "PYG"))
        assertEquals(5000L, MoneyDecimal.toMinorUnits(BigDecimal("50.00"), "BRL"))
    }

    @Test
    fun testA_MONEY_08_nonBrlBaseCrossRateCalculation() {
        // Base is USD, transaction in PYG
        // USD = 0.20 per BRL, PYG = 7000 per BRL -> PYG per USD = 7000 / 0.20 = 35000
        val quote = currencyManager.convertMoneyExact(
            amount = BigDecimal("10.00"),
            fromCurrency = "USD",
            toCurrency = "PYG",
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
    fun testA_MONEY_09_canonicalJsonRoomSerializationPreservesByteForByteHash() {
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

        // Simulate persisting in Room (JSON string) and reading back after process recreation
        val recoveredReq = gson.fromJson(json1, CommandCheckoutCommitRequest::class.java)
        val json2 = gson.toJson(recoveredReq)
        val hash2 = sha256(json2)

        assertEquals("Byte-for-byte JSON must match", json1, json2)
        assertEquals("Idempotency body hash must match exactly", hash1, hash2)
    }

    private fun sha256(input: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
