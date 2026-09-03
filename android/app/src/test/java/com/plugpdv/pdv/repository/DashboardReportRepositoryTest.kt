package com.plugpdv.pdv.repository

import com.google.gson.JsonNull
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.api.dto.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class DashboardReportRepositoryTest {
    private val service: PosApiService = mock()
    private val repository = ReportRepository(service)

    private fun stub(dto: DashboardReportDto = DashboardReportDto()) = runBlocking {
        whenever(service.getDashboardReport(any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any())).thenReturn(dto)
    }

    private fun fetch(period: DateFilterOption = DateFilterOption.TODAY, limit: Int = 50, offset: Int = 0) =
        runBlocking { repository.getReport("session-token", period, limit, offset) }

    @Test fun todaySendsToday() { stub(); fetch(); runBlocking { verify(service).getDashboardReport(any(), eq("TODAY"), anyOrNull(), anyOrNull(), anyOrNull(), any(), any()) } }
    @Test fun yesterdaySendsYesterday() { stub(); fetch(DateFilterOption.YESTERDAY); runBlocking { verify(service).getDashboardReport(any(), eq("YESTERDAY"), anyOrNull(), anyOrNull(), anyOrNull(), any(), any()) } }
    @Test fun allTimeSendsAllTime() { stub(); fetch(DateFilterOption.ALL_TIME); runBlocking { verify(service).getDashboardReport(any(), eq("ALL_TIME"), anyOrNull(), anyOrNull(), anyOrNull(), any(), any()) } }

    @Test fun brlAndPygSalesRemainSeparate() {
        stub(DashboardReportDto(salesByCurrency = listOf(CurrencyTotalDto("BRL", 42000, 2), CurrencyTotalDto("PYG", 102250, 1))))
        val result = fetch()
        assertEquals(listOf("BRL", "PYG"), result.sales.map { it.money.currency })
        assertEquals(listOf(42000L, 102250L), result.sales.map { it.money.amountMinor })
    }

    @Test fun canonicalNullIsAccepted() {
        stub(DashboardReportDto(canonical = null))
        assertFalse(fetch().hasCanonicalTotal)
        stub(DashboardReportDto(canonical = JsonNull.INSTANCE))
        assertFalse(fetch().hasCanonicalTotal)
    }

    @Test fun confirmedPaymentsAreMappedExactlyFromBackend() {
        stub(DashboardReportDto(paymentsByMethod = listOf(PaymentTotalDto("PIX", "BRL", 9900, 1))))
        val payment = fetch().payments.single()
        assertEquals("PIX", payment.method); assertEquals(9900L, payment.money.amountMinor)
    }

    @Test fun openReceivablesUseBackendResult() {
        stub(DashboardReportDto(openReceivables = listOf(OpenReceivableDto("PYG", 45000, 3))))
        val open = fetch().openReceivables.single()
        assertEquals("PYG", open.money.currency); assertEquals(45000L, open.money.amountMinor)
    }

    @Test fun cashOperationsDoNotInventTypes() {
        stub(DashboardReportDto(cashOperations = listOf(CashOperationDto("SANGRIA", "BRL", 5000, 1))))
        assertEquals(listOf("SANGRIA"), fetch().cashOperations.map { it.type })
    }

    @Test fun historyPaginationUsesRequestedLimitAndOffset() {
        stub(DashboardReportDto(history = listOf(ReportHistoryDto("s1", "2026-09-03T10:00:00Z", "BRL", 1000, "PIX", "Ana")), pagination = ReportPaginationDto(20, 40, 100, true)))
        val report = fetch(limit = 20, offset = 40)
        assertEquals(40, report.pagination.offset); assertTrue(report.pagination.hasMore)
        runBlocking { verify(service).getDashboardReport(any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), eq(20), eq(40)) }
    }

    @Test fun emptyReportIsAValidSuccess() { stub(); assertTrue(fetch().isEmpty) }

    @Test fun backendFailureIsRepresentedAsFailureForViewModelToIsolate() {
        runBlocking { whenever(service.getDashboardReport(any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any())).thenAnswer { throw java.io.IOException("offline") } }
        val result = runBlocking { repository.getReportResult("session-token", DateFilterOption.TODAY) }
        assertTrue(result.exceptionOrNull() is java.io.IOException)
    }
}
