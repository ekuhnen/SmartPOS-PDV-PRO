package com.plugpdv.pdv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.ui.sale.*
import com.plugpdv.pdv.utils.CurrencyManager
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CheckoutReceiptSequenceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs = context.getSharedPreferences("receipt-test", Context.MODE_PRIVATE)
    private val final = CheckoutUiState(paymentSuccess = true, isComandaClosed = true,
        balanceBaseMinor = 0L, paidBaseMinor = 43384L, totalBaseMinor = 43384L,
        authoritativeTotal = 43384.0, authoritativeSubtotal = 39440.0,
        authoritativeTaxAmount = 3944.0, authoritativeServiceFee = 0.0,
        baseCurrency = "PYG", baseMinorUnitDigits = 0,
        paymentsHistory = listOf(ComandaPaymentDto("pay1", "DINHEIRO", 43384.0, "PYG")))

    @Before fun reset() { prefs.edit().clear().commit() }

    private suspend fun deliver(state: CheckoutUiState, events: MutableList<String>, id: String = "evandro") {
        CheckoutReceiptSequence.deliver(state,
            transaction = { events.add("transaction") },
            closing = { if (CheckoutReceiptSequence.claimFinal(prefs, id)) events.add("closing:$id") },
            printError = { events.add("error") }, finish = { events.add("finish") })
    }

    @Test fun partialCashPrintsOnlyTransaction() = runBlocking {
        val events = mutableListOf<String>()
        deliver(final.copy(isComandaClosed = false, balanceBaseMinor = 200L), events)
        assertEquals(listOf("transaction", "finish"), events)
    }

    @Test fun finalCashPrintsBothBeforeFiscalFlow() = runBlocking {
        val events = mutableListOf<String>()
        deliver(final.copy(lastPaymentMethod = "DINHEIRO"), events)
        assertEquals(listOf("transaction", "closing:evandro", "finish"), events)
    }

    @Test fun cardWaitsForApprovedSettlement() = runBlocking {
        val events = mutableListOf<String>()
        deliver(final.copy(lastPaymentMethod = "CREDIT", isAwaitingProvider = true), events)
        deliver(final.copy(lastPaymentMethod = "CREDIT", isPendingSync = true), events)
        assertTrue(events.isEmpty())
        deliver(final.copy(lastPaymentMethod = "CREDIT"), events)
        assertEquals(listOf("transaction", "closing:evandro", "finish"), events)
    }

    @Test fun closedFlagAloneDoesNotReplaceCanonicalZeroBalance() = runBlocking {
        val events = mutableListOf<String>()
        deliver(final.copy(balanceBaseMinor = null), events)
        assertEquals(listOf("transaction", "finish"), events)
    }

    @Test fun repeatedEmissionsAndResumeDoNotDuplicateFinal() = runBlocking {
        val events = mutableListOf<String>()
        repeat(3) { deliver(final, events) }
        assertEquals(1, events.count { it == "closing:evandro" })
        val reopenedPrefs = context.getSharedPreferences("receipt-test", Context.MODE_PRIVATE)
        assertFalse(CheckoutReceiptSequence.claimFinal(reopenedPrefs, "evandro"))
        val before = events.toList()
        deliver(final.copy(paymentSuccess = false), events)
        assertEquals(before, events)
    }

    @Test fun delayedReceiptMustFinishBeforeFiscalDialog() = runBlocking {
        val receiptReady = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            CheckoutReceiptSequence.deliver(final, { events.add("transaction") },
                { receiptReady.await(); events.add("closing") },
                { events.add("error") }, { events.add("fiscal") })
        }
        assertEquals(listOf("transaction"), events)
        receiptReady.complete(Unit)
        job.join()
        assertEquals(listOf("transaction", "closing", "fiscal"), events)
    }

    @Test fun printerFailureNeverRetriesCommittedPaymentAndManualReprintBypassesClaim() = runBlocking {
        val events = mutableListOf<String>()
        CheckoutReceiptSequence.deliver(final, { events.add("transaction") }, {
            assertTrue(CheckoutReceiptSequence.claimFinal(prefs, "evandro"))
            error("printer unavailable")
        }, { events.add("print error") }, { events.add("finish") })
        assertEquals(listOf("transaction", "print error", "finish"), events)
        assertTrue(final.paymentSuccess)
        assertEquals(0L, final.balanceBaseMinor)
        assertFalse(CheckoutReceiptSequence.claimFinal(prefs, "evandro"))
        assertTrue(render(reprint = true).contains("43384") || render(reprint = true).contains("43.384"))
    }

    @Test fun transactionPrinterFailureDoesNotSuppressFinalReceipt() = runBlocking {
        val events = mutableListOf<String>()
        CheckoutReceiptSequence.deliver(final, { error("paper") },
            { events.add("closing") }, { events.add("error") }, { events.add("finish") })
        assertEquals(listOf("error", "closing", "finish"), events)
    }

    @Test fun independentComandasOnSameTableHaveIndependentFinalReceipts() = runBlocking {
        val events = mutableListOf<String>()
        deliver(final, events, "evandro")
        assertFalse(prefs.getBoolean("CLOSING_RECEIPT_ATTEMPTED_bk", false))
        deliver(final, events, "bk")
        assertEquals(1, events.count { it == "closing:evandro" })
        assertEquals(1, events.count { it == "closing:bk" })
        assertFalse(render().contains("bk"))
    }

    private fun render(reprint: Boolean = false, physicalId: String? = "mesa20"): String {
        val receipt = Gson().fromJson("""{"issuer":{"trade_name":"Restaurant","legal_name":"Legal SA",
            "document_type":"RUC","document_number":"12345","email":"issuer@example.com",
            "phone":"55555","address":{"line1":"Main Street","number":"20","city":"Asuncion"}},
            "customer":{"name":"Evandro","document_number":"98765"}}""", ComandaReceiptResponse::class.java)
        val table = Table(id = physicalId, number = 20, comandaId = "evandro", items = mutableListOf(
            TableItem(product = Product(id = "p1", name = "Heineken", selling_price = 11600.0, price_currency = "PYG"), quantity = 1)))
        return ComandaClosingReceiptRenderer.render(context, table, final, reprint, receipt)
    }

    @Test fun canonicalIssuerItemsPaymentsAndCustomerArePresent() {
        val text = render()
        listOf("Restaurant", "Legal SA", "12345", "Main Street", "issuer@example.com", "55555",
            "Heineken", "DINHEIRO", "Evandro", "98765", "evandro").forEach { assertTrue(it, text.contains(it)) }
    }

    @Test fun pygDoesNotConvertThroughBrl() {
        val cm = CurrencyManager.getInstance()
        val selection = cm.selectedCurrency
        try {
            cm.setRates(ExchangeResponse("PYG", listOf(ExchangeResponse.CurrencyRate("PYG", 1160.0))))
            cm.selectedCurrency = "USD"
            val text = render()
            assertTrue(text.contains("43.384"))
            assertTrue(text.contains("PYG"))
            assertFalse(text.contains("50.325.440"))
            assertFalse(text.contains("50,325,440"))
        } finally { cm.setRates(null); cm.selectedCurrency = selection }
    }

    @Test fun standaloneUsesComandaIdentityWithoutPhysicalTable() {
        assertTrue(render(physicalId = null).contains("evandro"))
    }

    @Test fun failedDispatchIsNotMarkedPrintedAndManualButtonCanReprint() = runBlocking {
        var dispatches = 0
        assertTrue(runCatching {
            CheckoutReceiptSequence.printFinal(prefs, "evandro", false) { dispatches++; false }
        }.isFailure)
        assertFalse(prefs.getBoolean("CLOSING_RECEIPT_PRINTED_evandro", false))
        CheckoutReceiptSequence.printFinal(prefs, "evandro", false) { dispatches++; true }
        assertEquals(1, dispatches)
        CheckoutReceiptSequence.printFinal(prefs, "evandro", true) { dispatches++; true }
        assertEquals(2, dispatches)
    }
}
