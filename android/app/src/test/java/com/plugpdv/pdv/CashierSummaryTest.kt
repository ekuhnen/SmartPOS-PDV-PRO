package com.plugpdv.pdv

import com.plugpdv.pdv.models.DashboardMoney
import com.plugpdv.pdv.ui.cashier.CashierViewModel
import org.junit.Assert.assertEquals
import org.junit.Test

class CashierSummaryTest {
    @Test fun collectedCurrenciesRemainSeparate() {
        val s = CashierViewModel.CashSummary(collectedByMethod = mapOf(
            "DINHEIRO" to listOf(DashboardMoney(30_000, "PYG")),
            "PIX" to listOf(DashboardMoney(12_00, "BRL"))
        ))
        assertEquals(setOf("PYG"), s.cashToRender().map { it.currency }.toSet())
        assertEquals(30_000L, s.cashToRender().single().amountMinor)
    }

    @Test fun pixDoesNotContributeToPhysicalCash() {
        val s = CashierViewModel.CashSummary(collectedByMethod = mapOf("PIX" to listOf(DashboardMoney(50_000, "PYG"))))
        assertEquals(emptyList<DashboardMoney>(), s.cashToRender())
    }

    @Test fun sangriaReducesCashInSameCurrency() {
        val s = CashierViewModel.CashSummary(
            collectedByMethod = mapOf("CASH" to listOf(DashboardMoney(30_000, "PYG"))),
            withdrawals = listOf(DashboardMoney(5_000, "PYG"))
        )
        assertEquals(25_000L, s.cashToRender().single().amountMinor)
    }

    @Test fun openingIsKeptSeparateFromCollected() {
        val opening = DashboardMoney(10_000, "PYG")
        val s = CashierViewModel.CashSummary(opening = listOf(opening), collectedByMethod = emptyMap())
        assertEquals(listOf(opening), s.opening)
        assertEquals(emptyList<DashboardMoney>(), s.cashToRender())
    }
}
