package com.plugpdv.pdv

import com.google.gson.Gson
import com.plugpdv.pdv.models.*
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal

class CashSessionContractTest {
    private val gson = Gson()

    @Test fun previousSessionCurrencyCannotEnterCurrentSession() {
        val json = """{"session":{"session_id":"s2","opened_at":"2026-09-04T10:00:00Z","status":"OPEN","positions_by_currency":[{"currency":"PYG","opening":15000,"cash_receipts":0,"cash_payins":0,"withdrawals":0,"cash_refunds":0,"expected_cash":15000}],"payments_by_method":[]}}"""
        val current = gson.fromJson(json, CashSessionResponse::class.java).session!!
        assertEquals(listOf("PYG"), current.cashPositions().map { it.currency })
        assertFalse(gson.toJson(current).contains("2.10"))
    }

    @Test fun physicalExpectedAndPaymentSummaryAreIndependentBackendFields() {
        val position = CashPosition("PYG", bd(15000), bd(20000), bd(0), bd(5000), bd(0), bd(30000))
        val pix = CashPaymentSummary("PYG", "PIX", false, 1, bd(30000))
        assertEquals(bd(30000), position.expectedCash)
        assertFalse(pix.isCash)
        assertEquals(bd(30000), pix.amount)
    }

    @Test fun currenciesRemainSeparate() {
        val positions = listOf(
            CashPosition("PYG", bd(15000), bd(0), bd(0), bd(5000), bd(0), bd(10000)),
            CashPosition("BRL", bd("2.10"), bd(0), bd(0), bd(0), bd(0), bd("2.10")))
        assertEquals(setOf("PYG", "BRL"), positions.map { it.currency }.toSet())
    }

    @Test fun blankCountIsOmittedAndZeroIsExplicit() {
        val blank = CashierRequest("fechar", sessionId = "s", countedByCurrency = null)
        val zero = CashierRequest("fechar", sessionId = "s", countedByCurrency = listOf(CountedCashRequest("PYG", BigDecimal.ZERO)))
        assertFalse(gson.toJson(blank).contains("counted_by_currency"))
        assertTrue(gson.toJson(zero).contains("\"counted_amount\":0"))
    }

    @Test fun previewAndZUseBackendSnapshotValues() {
        val response = CashActionResponse(sessionId = "s", status = "CLOSED", positions = listOf(
            CashPosition("PYG", bd(15000), bd(20000), bd(0), bd(5000), bd(0), bd(30000), bd(30000), bd(0))))
        assertEquals(bd(30000), response.toSnapshot().cashPositions().single().expectedCash)
        assertEquals(bd(0), response.toSnapshot().cashPositions().single().variance)
    }

    private fun bd(value: Int) = BigDecimal(value)
    private fun bd(value: String) = BigDecimal(value)
}
