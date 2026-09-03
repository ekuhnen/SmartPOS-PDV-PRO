package com.plugpdv.pdv.repository

import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.api.dto.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*
import java.math.BigDecimal
import com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider

class DashboardReportRepositoryTest {
    private val service: PosApiService = mock()
    private val repository = ReportRepository(service)

    private fun dto(
        sales: List<CurrencyTotalDto> = emptyList(), payments: List<PaymentTotalDto> = emptyList(),
        receivables: List<OpenReceivableDto> = emptyList(), operations: List<CashOperationDto> = emptyList(),
        history: List<ReportHistoryDto> = emptyList(), limit: Int = 50, offset: Int = 0
    ) = DashboardReportDto("dashboard_v1", null, CompletedSalesDto(sales, null), payments, receivables, operations, ReportHistoryPageDto(limit, offset, history), ReportNotesDto(null, "NO_FORCED_CROSS_CURRENCY_TOTAL"))

    private fun stub(value: DashboardReportDto = dto()) = runBlocking {
        whenever(service.getDashboardReport(any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any())).thenReturn(value)
    }
    private fun fetch(period: DateFilterOption = DateFilterOption.TODAY, limit: Int = 50, offset: Int = 0) = runBlocking { repository.getReport("token", period, limit, offset) }

    @Test fun todaySendsToday() { stub(); fetch(); runBlocking { verify(service).getDashboardReport(any(), eq("TODAY"), anyOrNull(), anyOrNull(), anyOrNull(), any(), any()) } }
    @Test fun yesterdaySendsYesterday() { stub(); fetch(DateFilterOption.YESTERDAY); runBlocking { verify(service).getDashboardReport(any(), eq("YESTERDAY"), anyOrNull(), anyOrNull(), anyOrNull(), any(), any()) } }
    @Test fun allTimeSendsAllTime() { stub(); fetch(DateFilterOption.ALL_TIME); runBlocking { verify(service).getDashboardReport(any(), eq("ALL_TIME"), anyOrNull(), anyOrNull(), anyOrNull(), any(), any()) } }

    @Test fun deployedObjectShapesDeserialize() {
        val json = """{"protocol":"dashboard_v1","period":{"requested":"TODAY","timezone":"America/Sao_Paulo","resolvedBy":"backend"},"completedSales":{"byCurrency":[{"currency":"BRL","count":2,"total":19.53,"canonical":null}],"coverage":{"transactionCurrencyAuthoritativeCount":2,"canonicalBaseAvailableCount":0,"canonicalBaseUnavailableCount":2}},"paymentsByMethod":[],"openReceivables":[{"currency":"BRL","comandas":7,"totalLiquido":207,"totalPago":0,"remaining":207,"requiresReconciliationCount":0,"source":"comanda_money_summary"}],"cashOperations":[],"history":{"limit":50,"offset":0,"items":[]},"notes":{"grandTotal":null,"grandTotalReason":"NO_FORCED_CROSS_CURRENCY_TOTAL"}}"""
        val parsed = Gson().fromJson(json, DashboardReportDto::class.java)
        assertEquals(BigDecimal("19.53"), parsed.completedSales!!.byCurrency!!.single().total)
        assertEquals(50, parsed.history!!.limit)
    }

    @Test fun brlAndPygSalesRemainSeparateInMinorUnits() {
        stub(dto(sales = listOf(CurrencyTotalDto("BRL", 2, BigDecimal("420.00"), null), CurrencyTotalDto("PYG", 1, BigDecimal("102250"), null))))
        val result = fetch()
        assertEquals(listOf("BRL", "PYG"), result.sales.map { it.money.currency })
        assertEquals(listOf(42000L, 102250L), result.sales.map { it.money.amountMinor })
    }

    @Test fun pyg30000RendersWithoutApplyingExchangeRate() {
        val provider = DefaultCurrencyRulesProvider()
        assertEquals("Gs. 30.000", provider.formatMinorUnits(30_000L, "PYG"))
        assertNotEquals("Gs. 35.040.000", provider.formatMinorUnits(30_000L, "PYG"))
    }

    @Test fun totalsByCurrencyContractIsConsumedDirectly() {
        val value = dto().copy(totalsByCurrency = listOf(CurrencyTotalDto("PYG", 1, BigDecimal("30000"), null)))
        stub(value)
        val result = fetch()
        assertEquals("PYG", result.sales.single().money.currency)
        assertEquals(30_000L, result.sales.single().money.amountMinor)
    }

    @Test fun canonicalNullIsAccepted() { stub(); assertFalse(fetch().hasCanonicalTotal) }

    @Test fun confirmedPaymentsAreMappedFromFormaAndTotal() {
        stub(dto(payments = listOf(PaymentTotalDto("cash", "DINHEIRO", "BRL", 3, BigDecimal("61.68"), ExcludedPaymentCountsDto(2, 0)))))
        val payment = fetch().payments.single()
        assertEquals("DINHEIRO", payment.method); assertEquals(6168L, payment.money.amountMinor)
    }

    @Test fun openReceivablesUseRemaining() {
        stub(dto(receivables = listOf(OpenReceivableDto("PYG", 7, BigDecimal("300000"), BigDecimal("100000"), BigDecimal("200000"), 0, "comanda_money_summary"))))
        assertEquals(200000L, fetch().openReceivables.single().money.amountMinor)
    }

    @Test fun cashOperationsUseReturnedTipoOnly() {
        stub(dto(operations = listOf(CashOperationDto("ABERTURA", "BRL", "company_default_currency", 1, BigDecimal.ZERO))))
        assertEquals(listOf("ABERTURA"), fetch().cashOperations.map { it.type })
    }

    @Test fun historyObjectSupportsPagination() {
        val item = ReportHistoryDto("s1", "2026-09-03T10:00:00Z", "2026-09-03", BigDecimal("10.00"), "BRL", "pix", "PIX", null, null, "Ana")
        stub(dto(history = listOf(item), limit = 20, offset = 40))
        val report = fetch( limit = 20, offset = 40)
        assertEquals(40, report.pagination.offset); assertEquals(1000L, report.history.single().money.amountMinor)
    }

    @Test fun emptyReportIsValidSuccess() { stub(); assertTrue(fetch().isEmpty) }

    @Test fun backendFailureIsIsolatedAsResultFailure() {
        runBlocking { whenever(service.getDashboardReport(any(), any(), anyOrNull(), anyOrNull(), anyOrNull(), any(), any())).thenAnswer { throw java.io.IOException("offline") } }
        assertTrue(runBlocking { repository.getReportResult("token", DateFilterOption.TODAY) }.isFailure)
    }
}
