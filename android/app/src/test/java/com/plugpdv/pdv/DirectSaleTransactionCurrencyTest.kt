package com.plugpdv.pdv

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.plugpdv.pdv.models.SaleItem
import com.plugpdv.pdv.models.SaleRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.math.BigDecimal

class DirectSaleTransactionCurrencyTest {
    private val gson = Gson()

    @Test fun pygCheckoutSerializesPygTransactionCurrency() {
        assertEquals("PYG", jsonFor("PYG", "PYG").get("transaction_currency").asString)
    }

    @Test fun brlCheckoutSerializesBrlTransactionCurrency() {
        assertEquals("BRL", jsonFor("BRL", "BRL").get("transaction_currency").asString)
    }

    @Test fun explicitTransactionCurrencyDoesNotFollowCompanyDefault() {
        assertEquals("PYG", requestFor("PYG", "PYG", "BRL").transactionCurrency)
        assertEquals("BRL", requestFor("BRL", "BRL", "PYG").transactionCurrency)
    }

    @Test fun transactionAndPaymentCurrencyRemainSeparateFields() {
        val json = jsonFor("PYG", "USD")
        assertEquals("PYG", json.get("transaction_currency").asString)
        assertEquals("USD", json.get("payment_currency").asString)
    }

    @Test fun persistedPayloadAndRetryKeepTransactionCurrency() {
        val persisted = gson.toJson(requestFor("PYG", "PYG", "BRL"))
        val restored = gson.fromJson(persisted, SaleRequest::class.java)
        val retry = JsonParser.parseString(gson.toJson(restored)).asJsonObject
        assertEquals("PYG", retry.get("transaction_currency").asString)
    }

    @Test fun networkProjectionLeavesServerMoneyAuthorityOnServer() {
        val wire = JsonParser.parseString(gson.toJson(requestFor("PYG", "PYG").toCreateRequest())).asJsonObject
        assertEquals("PYG", wire.get("transaction_currency").asString)
        listOf("tax_amount", "tax_snapshot", "subtotal", "total", "converted_total", "cash_amount_due")
            .forEach { assertFalse("$it must not be sent", wire.has(it)) }
    }

    private fun jsonFor(transaction: String, payment: String) =
        JsonParser.parseString(gson.toJson(requestFor(transaction, payment))).asJsonObject

    private fun requestFor(transaction: String, payment: String, legacyCurrency: String = transaction) = SaleRequest(
        customerName = "Consumidor Final", total = BigDecimal("22968"),
        items = listOf(SaleItem("product-1", "Produto", 1, 18.0)), paymentMethod = "PIX",
        currency = legacyCurrency, transactionCurrency = transaction, paymentCurrency = payment,
        exchangeRatesSnapshot = mapOf("PYG" to "1160")
    )
}
