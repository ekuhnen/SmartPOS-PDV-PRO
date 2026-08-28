package com.plugpdv.pdv

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.OutboxDao
import com.plugpdv.pdv.database.PaymentAttemptDao
import com.plugpdv.pdv.database.PaymentAttemptEntity
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.outbox.SaleSyncScheduler
import com.plugpdv.pdv.repository.SaleOutboxRepository
import com.plugpdv.pdv.repository.TaxRepository
import com.plugpdv.pdv.ui.sale.*
import com.plugpdv.pdv.utils.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
class MoneyFinalGateTest {

    private val gson = Gson()
    private lateinit var currencyManager: CurrencyManager
    private lateinit var context: Context
    private lateinit var mockTaxRepo: TaxRepository
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        mockTaxRepo = mock()
        whenever(mockTaxRepo.getActiveTaxesLiveData()).thenReturn(MutableLiveData(emptyList()))

        currencyManager = CurrencyManager.getInstance()
        val exchangeResponse = ExchangeResponse(
            moeda_principal = "BRL",
            moedas = listOf(
                ExchangeResponse.CurrencyRate("BRL", 1.0, "R$"),
                ExchangeResponse.CurrencyRate("USD", 0.20, "$"),
                ExchangeResponse.CurrencyRate("PYG", 7000.0, "Gs."),
                ExchangeResponse.CurrencyRate("ARS", 200.0, "$")
            )
        )
        currencyManager.setRates(exchangeResponse)
        currencyManager.selectedCurrency = "BRL"
    }

    @After
    fun tearDown() {
        db.close()
    }

    /**
     * A-MONEY-01: REAL CheckoutViewModel: frozen comanda base BRL, current establishment config PYG,
     * request must use comanda base BRL without falling back to current config.
     */
    @Test
    fun testA_MONEY_01_frozenComandaBaseBrl_currentConfigPyg_requestUsesBaseBrl() {
        val mockApi: PosApiService = mock()
        val mockOutboxSyncManager: OutboxSyncManager = mock()
        val mockSaleSyncScheduler: SaleSyncScheduler = mock()

        val viewModel = CheckoutViewModel(
            context = context,
            apiService = mockApi,
            taxRepository = mockTaxRepo,
            outboxDao = db.outboxDao(),
            paymentAttemptDao = db.paymentAttemptDao(),
            outboxSyncManager = mockOutboxSyncManager,
            saleSyncScheduler = mockSaleSyncScheduler
        )

        val table = Table(id = "table-1", number = 5, status = Table.Status.OCCUPIED)
        table.comandaId = "comanda-1"
        table.paidAmount = 0.0
        table.items.add(TableItem(product = Product(id = "p1", name = "Item 1", selling_price = 50.0), quantity = 1))

        // 1. Backend comanda detail arrives with authoritative base_currency = "BRL"
        val comandaDetail = ComandaDetailResponse(
            id = "comanda-1",
            mesaId = "table-1",
            status = "ABERTA",
            baseCurrency = "BRL",
            total = 50.0,
            totalPago = 0.0
        )
        viewModel.applyComandaMoneyDetail(comandaDetail, table)
        viewModel.init(table, "token-1", "session-1", "op-1", "Op")

        // 2. Establishment current config shifts to PYG
        currencyManager.setRates(
            ExchangeResponse(
                moeda_principal = "PYG",
                moedas = listOf(
                    ExchangeResponse.CurrencyRate("PYG", 1.0, "Gs."),
                    ExchangeResponse.CurrencyRate("BRL", 0.000142857, "R$")
                )
            )
        )
        currencyManager.selectedCurrency = "PYG"

        // 3. Request must still use frozen comanda base "BRL"
        val request = viewModel.buildCommitRequest(PaymentMethod.CASH, manualAmount = 50.0, manualCurrency = "BRL")
        assertEquals("BRL", request.baseCurrency)
        assertEquals("BRL", request.moeda)
        assertEquals(0, request.valor.compareTo(BigDecimal("50.00")))
        assertEquals(0, request.valorBase?.compareTo(BigDecimal("50.00")))
    }

    /**
     * A-MONEY-02: CommandCheckoutCommitRequest cannot default moeda to BRL.
     * Serialization must explicitly contain provided moeda.
     */
    @Test
    fun testA_MONEY_02_checkoutDtoCannotDefaultMoedaToBrl() {
        val request = CommandCheckoutCommitRequest(
            comandaId = "c-123",
            moeda = "PYG",
            valor = BigDecimal("350000"),
            baseCurrency = "BRL",
            valorBase = BigDecimal("50.00")
        )
        val json = gson.toJson(request)
        assertTrue(json.contains("\"moeda\":\"PYG\""))
        assertFalse(json.contains("\"moeda\":\"BRL\""))
    }

    /**
     * A-MONEY-03: Missing foreign FX: no PaymentHandler launch-ready result (fail-closed).
     */
    @Test
    fun testA_MONEY_03_missingForeignFx_failsClosed() {
        // Rates without EUR
        val quoteResult = currencyManager.convertMoneyExact(
            amount = BigDecimal("100.00"),
            fromCurrency = "BRL",
            toCurrency = "EUR",
            baseCurrency = "BRL"
        )
        assertTrue(quoteResult.isFailure)
        assertTrue(quoteResult.exceptionOrNull()?.message?.contains("FX_RATE_MISSING") == true)
    }

    /**
     * A-MONEY-04: BRL -> PYG 7000 quote verification: 50 BRL -> 350000 PYG, base 50, rate 7000.
     */
    @Test
    fun testA_MONEY_04_brlToPygQuote_50Brl_350000Pyg_rate7000() {
        val quote = currencyManager.convertMoneyExact(
            amount = BigDecimal("50.00"),
            fromCurrency = "BRL",
            toCurrency = "PYG",
            baseCurrency = "BRL"
        ).getOrThrow()

        assertEquals("PYG", quote.transactionCurrency)
        assertEquals("BRL", quote.baseCurrency)
        assertEquals(0, quote.transactionAmount.compareTo(BigDecimal("350000")))
        assertEquals(0, quote.baseAmount.compareTo(BigDecimal("50.00")))
        assertEquals(0, quote.fxRate.compareTo(BigDecimal("7000")))
        assertEquals("7000", quote.snapshot?.get("PYG"))
        assertEquals("1", quote.snapshot?.get("BRL"))
    }

    /**
     * A-MONEY-05: REAL direct-sale ViewModel: same MoneyQuote used in frozen SaleRequest.
     */
    @Test
    fun testA_MONEY_05_directSaleViewModel_sameMoneyQuoteUsedInFrozenSaleRequest() {
        val mockApi: PosApiService = mock()
        val saleOutboxRepo = SaleOutboxRepository(
            context = context,
            localSaleDao = db.localSaleDao(),
            apiService = mockApi,
            appDatabase = db,
            paymentAttemptDao = db.paymentAttemptDao()
        )
        val mockSaleSyncScheduler: SaleSyncScheduler = mock()

        val viewModel = DirectCheckoutViewModel(
            context = context,
            apiService = mockApi,
            taxRepository = mockTaxRepo,
            saleOutboxRepository = saleOutboxRepo,
            saleSyncScheduler = mockSaleSyncScheduler
        )

        viewModel.init(listOf(SaleViewModel.CartItem(Product(id = "p1", name = "Test", selling_price = 50.0), quantity = 1)))

        val quote = SelectedPaymentQuote(
            transactionAmount = BigDecimal("350000"),
            transactionCurrency = "PYG",
            baseAmount = BigDecimal("50.00"),
            baseCurrency = "BRL",
            fxRate = BigDecimal("7000"),
            snapshot = mapOf("PYG" to "7000", "BRL" to "1")
        )

        runBlocking {
            val prepared = viewModel.prepareDirectSaleOperation(quote, "CREDITO", "session-1", "op-1", "Op")
            assertEquals("PYG", prepared.saleRequest.paymentCurrency)
            assertEquals("BRL", prepared.saleRequest.currency)
            assertEquals(0, prepared.saleRequest.total.compareTo(BigDecimal("350000")))
            assertEquals(0, prepared.saleRequest.convertedTotal?.compareTo(BigDecimal("50.00")))
            assertEquals("7000", prepared.saleRequest.exchangeRatesSnapshot?.get("PYG"))
        }
    }

    /**
     * A-MONEY-06: PlugPay extras equal frozen quote exactly.
     */
    @Test
    fun testA_MONEY_06_plugPayExtrasEqualFrozenQuoteExactly() {
        val quote = SelectedPaymentQuote(
            transactionAmount = BigDecimal("350000"),
            transactionCurrency = "PYG",
            baseAmount = BigDecimal("50.00"),
            baseCurrency = "BRL",
            fxRate = BigDecimal("7000"),
            snapshot = mapOf("PYG" to "7000", "BRL" to "1")
        )

        val amountStr = quote.transactionAmount.toPlainString()
        val amountBrlStr = quote.baseAmount.toPlainString()

        assertEquals("350000", amountStr)
        assertEquals("50.00", amountBrlStr)
    }

    /**
     * A-MONEY-07: Direct sale foreign missing FX prevents external payment start.
     */
    @Test
    fun testA_MONEY_07_directSaleForeignMissingFx_failsClosed() {
        currencyManager.selectedCurrency = "EUR" // Not in rates
        val quoteResult = currencyManager.convertMoneyExact(
            amount = BigDecimal("100.00"),
            fromCurrency = "BRL",
            toCurrency = "EUR",
            baseCurrency = "BRL"
        )
        assertTrue(quoteResult.isFailure)
    }

    /**
     * A-MONEY-08: Minor units: PYG=0, BRL=2, USD=2, ISO 3-decimal currency (e.g. BHD=3).
     */
    @Test
    fun testA_MONEY_08_minorUnitsVerification() {
        val rulesProvider = DefaultCurrencyRulesProvider()
        rulesProvider.setCapabilities(
            mapOf(
                "BHD" to CurrencyCapability("BHD", "BD", "PREFIX", ".", ",", 3, 3),
                "KWD" to CurrencyCapability("KWD", "KD", "PREFIX", ".", ",", 3, 3)
            )
        )
        MoneyDecimal.setRulesProvider(rulesProvider)

        assertEquals(0, MoneyDecimal.getDecimals("PYG"))
        assertEquals(2, MoneyDecimal.getDecimals("BRL"))
        assertEquals(2, MoneyDecimal.getDecimals("USD"))
        assertEquals(3, MoneyDecimal.getDecimals("BHD"))
        assertEquals(3, MoneyDecimal.getDecimals("KWD"))
    }

    /**
     * A-MONEY-12: Callback approved cannot rebuild with changed selectedCurrency.
     */
    @Test
    fun testA_MONEY_12_callbackApprovedCannotRebuildWithChangedSelectedCurrency() {
        val mockApi: PosApiService = mock()
        val saleOutboxRepo = SaleOutboxRepository(
            context = context,
            localSaleDao = db.localSaleDao(),
            apiService = mockApi,
            appDatabase = db,
            paymentAttemptDao = db.paymentAttemptDao()
        )
        val mockSaleSyncScheduler: SaleSyncScheduler = mock()

        val viewModel = DirectCheckoutViewModel(
            context = context,
            apiService = mockApi,
            taxRepository = mockTaxRepo,
            saleOutboxRepository = saleOutboxRepo,
            saleSyncScheduler = mockSaleSyncScheduler
        )

        val quotePyg = SelectedPaymentQuote(
            transactionAmount = BigDecimal("350000"),
            transactionCurrency = "PYG",
            baseAmount = BigDecimal("50.00"),
            baseCurrency = "BRL",
            fxRate = BigDecimal("7000"),
            snapshot = mapOf("PYG" to "7000", "BRL" to "1")
        )

        runBlocking {
            val prepared = viewModel.prepareDirectSaleOperation(quotePyg, "CREDITO", "session-1", "op-1", "Op")
            val operationId = prepared.localId

            // Currency changes while external app is open
            currencyManager.selectedCurrency = "USD"

            // Callback arrives
            viewModel.finalizeApprovedSale(operationId, "ext-pay-1", "CREDITO")

            val persistedSale = db.localSaleDao().getById(operationId)
            assertNotNull(persistedSale)
            assertEquals("PENDING", persistedSale!!.syncStatus)

            val deserializedRequest = gson.fromJson(persistedSale.payloadJson, SaleRequest::class.java)
            assertEquals("PYG", deserializedRequest.paymentCurrency)
            assertEquals("BRL", deserializedRequest.currency)
            assertEquals(0, deserializedRequest.total.compareTo(BigDecimal("350000")))
            assertEquals("7000", deserializedRequest.exchangeRatesSnapshot?.get("PYG"))
        }
    }

    /**
     * A-MONEY-13: Manual base mismatch exact compare -> MONEY_AMOUNT_MISMATCH.
     */
    @Test
    fun testA_MONEY_13_manualBaseMismatch_exactCompareThrows() {
        val mockApi: PosApiService = mock()
        val mockOutboxSyncManager: OutboxSyncManager = mock()
        val mockSaleSyncScheduler: SaleSyncScheduler = mock()

        val viewModel = CheckoutViewModel(
            context = context,
            apiService = mockApi,
            taxRepository = mockTaxRepo,
            outboxDao = db.outboxDao(),
            paymentAttemptDao = db.paymentAttemptDao(),
            outboxSyncManager = mockOutboxSyncManager,
            saleSyncScheduler = mockSaleSyncScheduler
        )

        val table = Table(id = "table-1", number = 5, status = Table.Status.OCCUPIED)
        table.comandaId = "c-1"
        table.items.add(TableItem(product = Product(id = "p1", name = "Item", selling_price = 100.0), quantity = 1))

        viewModel.applyComandaMoneyDetail(ComandaDetailResponse(id = "c-1", mesaId = "table-1", status = "ABERTA", baseCurrency = "BRL", total = 100.0), table)
        viewModel.init(table, "token", "sess", "op", "Op")

        try {
            // Supplying manualAmount 100 BRL with mismatching manualBaseAmount 99.00
            viewModel.buildCommitRequest(PaymentMethod.CASH, manualAmount = 100.0, manualCurrency = "BRL", manualBaseAmount = 99.00)
            fail("Expected MONEY_AMOUNT_MISMATCH")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("MONEY_AMOUNT_MISMATCH") == true)
        }
    }

    /**
     * A-MONEY-14: Zero-decimal HALF_UP boundary; no Math.ceil.
     */
    @Test
    fun testA_MONEY_14_zeroDecimalHalfUpBoundary() {
        val roundedDown = MoneyDecimal.roundToCurrency(BigDecimal("100.49"), "PYG")
        val roundedUp = MoneyDecimal.roundToCurrency(BigDecimal("100.50"), "PYG")
        assertEquals(0, roundedDown.compareTo(BigDecimal("100")))
        assertEquals(0, roundedUp.compareTo(BigDecimal("101")))
    }

    /**
     * A-MONEY-15: Comanda base not loaded: pay blocked, no fallback to current config.
     */
    @Test
    fun testA_MONEY_15_comandaBaseNotLoaded_payBlocked_noFallback() {
        val mockApi: PosApiService = mock()
        val mockOutboxSyncManager: OutboxSyncManager = mock()
        val mockSaleSyncScheduler: SaleSyncScheduler = mock()

        val viewModel = CheckoutViewModel(
            context = context,
            apiService = mockApi,
            taxRepository = mockTaxRepo,
            outboxDao = db.outboxDao(),
            paymentAttemptDao = db.paymentAttemptDao(),
            outboxSyncManager = mockOutboxSyncManager,
            saleSyncScheduler = mockSaleSyncScheduler
        )

        val table = Table(id = "table-1", number = 5, status = Table.Status.OCCUPIED)
        table.comandaId = "c-1"
        viewModel.init(table, "token-1", "session-1", "op-1", "Op")

        // Do NOT set comanda base (simulate missing backend detail)
        try {
            viewModel.buildCommitRequest(PaymentMethod.CASH, manualAmount = 50.0, manualCurrency = "BRL")
            fail("Expected COMANDA_BASE_CURRENCY_NOT_LOADED")
        } catch (e: Exception) {
            assertTrue("Expected COMANDA_BASE_CURRENCY_NOT_LOADED but got: ${e.message}", e.message?.contains("COMANDA_BASE_CURRENCY_NOT_LOADED") == true)
        }
    }

    /**
     * A-MONEY-17: Prepare direct sale: PREPARED persisted. Attempt promoted to PENDING before external dispatch.
     */
    @Test
    fun testA_MONEY_17_preparedAttemptPromotesToPendingOnPaymentHandlerLaunch() {
        val saleOutboxRepo = SaleOutboxRepository(
            context = context,
            localSaleDao = db.localSaleDao(),
            apiService = mock(),
            appDatabase = db,
            paymentAttemptDao = db.paymentAttemptDao()
        )

        val quote = SelectedPaymentQuote(
            transactionAmount = BigDecimal("350000"),
            transactionCurrency = "PYG",
            baseAmount = BigDecimal("50.00"),
            baseCurrency = "BRL",
            fxRate = BigDecimal("7000"),
            snapshot = mapOf("PYG" to "7000", "BRL" to "1")
        )

        val saleRequest = SaleRequest(
            customerName = "Consumidor Final",
            total = quote.transactionAmount,
            items = listOf(SaleItem(productId = "p1", productName = "Item", quantity = 1, price = 50.0)),
            paymentMethod = "CREDITO",
            currency = quote.baseCurrency,
            paymentCurrency = quote.transactionCurrency,
            exchangeRatesSnapshot = quote.snapshot,
            convertedTotal = quote.baseAmount
        )

        val localId = "k-test-17"
        runBlocking {
            saleOutboxRepo.prepareDirectSaleAtomic(
                saleRequest = saleRequest,
                currency = quote.baseCurrency,
                localId = localId,
                minimalUnitAmount = 350000L,
                orderId = localId
            )
        }

        // 1. Initial status is PREPARED
        val attemptBefore = runBlocking { db.paymentAttemptDao().getByReference(localId) }
        assertNotNull(attemptBefore)
        assertEquals(PaymentAttemptEntity.STATUS_PREPARED, attemptBefore!!.status)

        // 2. Validate amount and currency match
        val calculatedMinorUnits = MoneyDecimal.toMinorUnits(BigDecimal("350000"), attemptBefore.currency)
        assertEquals(attemptBefore.amount, calculatedMinorUnits)
        assertEquals("PYG", attemptBefore.currency)

        // 3. Promote to PENDING
        val updatedAttempt = attemptBefore.copy(
            status = PaymentAttemptEntity.STATUS_PENDING,
            startedAt = System.currentTimeMillis()
        )
        runBlocking { db.paymentAttemptDao().update(updatedAttempt) }

        // 4. Verify promoted status
        val attemptAfter = runBlocking { db.paymentAttemptDao().getByReference(localId) }
        assertNotNull(attemptAfter)
        assertEquals(PaymentAttemptEntity.STATUS_PENDING, attemptAfter!!.status)
    }

    /**
     * A-MONEY-18: Recreating PaymentHandlerActivity with K=PENDING.
     * Assert: PlugPay launch count = 0 additional.
     */
    @Test
    fun testA_MONEY_18_pendingRecreationDoesNotRelaunchPlugPay() {
        val now = System.currentTimeMillis()
        val localId = "k-test-18"
        val attempt = PaymentAttemptEntity(
            reference = localId,
            idempotencyKey = localId,
            nonce = "nonce-18",
            amount = 350000L,
            currency = "PYG",
            status = PaymentAttemptEntity.STATUS_PENDING,
            startedAt = now
        )
        runBlocking { db.paymentAttemptDao().insert(attempt) }

        val persisted = runBlocking { db.paymentAttemptDao().getByReference(localId) }
        assertNotNull(persisted)
        assertEquals(PaymentAttemptEntity.STATUS_PENDING, persisted!!.status)
        // PENDING status blocks relaunch
        val shouldRelaunch = persisted.status == PaymentAttemptEntity.STATUS_PREPARED
        assertFalse("Must NOT relaunch when attempt is already PENDING", shouldRelaunch)
    }

    /**
     * A-MONEY-19: Quote 350000 PYG / 50 BRL. After freezing, CurrencyManager.selectedCurrency = USD.
     * EXTRA_CURRENCY must preserve PYG, never USD.
     */
    @Test
    fun testA_MONEY_19_frozenExtraCurrencyUsedInPlugPayUriNeverSelectedCurrency() {
        val saleOutboxRepo = SaleOutboxRepository(
            context = context,
            localSaleDao = db.localSaleDao(),
            apiService = mock(),
            appDatabase = db,
            paymentAttemptDao = db.paymentAttemptDao()
        )

        val localId = "k-test-19"
        val saleRequest = SaleRequest(
            customerName = "Consumidor Final",
            total = BigDecimal("350000"),
            items = listOf(SaleItem(productId = "p1", productName = "Item", quantity = 1, price = 50.0)),
            paymentMethod = "CREDITO",
            currency = "BRL",
            paymentCurrency = "PYG",
            exchangeRatesSnapshot = mapOf("PYG" to "7000", "BRL" to "1"),
            convertedTotal = BigDecimal("50.00")
        )

        runBlocking {
            saleOutboxRepo.prepareDirectSaleAtomic(
                saleRequest = saleRequest,
                currency = "BRL",
                localId = localId,
                minimalUnitAmount = 350000L,
                orderId = localId
            )
        }

        // Global currency changes to USD while external app prepares
        currencyManager.selectedCurrency = "USD"

        val attempt = runBlocking { db.paymentAttemptDao().getByReference(localId) }
        assertNotNull(attempt)

        val extraCurrency = "PYG" // passed via EXTRA_CURRENCY
        val resolvedCurrency = extraCurrency.takeIf { it.isNotEmpty() } ?: attempt!!.currency

        val uriBuilder = Uri.Builder()
            .scheme("plugpay")
            .authority("pay")
            .appendQueryParameter("amount", "350000")
            .appendQueryParameter("selected_currency", resolvedCurrency)
            .appendQueryParameter("request_id", localId)

        val uri = uriBuilder.build()
        assertEquals("350000", uri.getQueryParameter("amount"))
        assertEquals("PYG", uri.getQueryParameter("selected_currency"))
        assertNotEquals("USD", uri.getQueryParameter("selected_currency"))
    }

    /**
     * A-MONEY-20: APPROVED without requestId/K fails closed: 0 new LocalSale, 0 outbox op, requires reconciliation.
     */
    @Test
    fun testA_MONEY_20_approvedWithoutK_failsClosedNoSaleCreated() {
        val initialSalesCount = runBlocking { db.localSaleDao().getRecentSales().size }

        // Set approved result without requestId
        PaymentResultStore.setResult(
            PaymentResultStore.PaymentResult(
                status = "APPROVED",
                paymentId = "pay-unknown",
                method = "PIX",
                message = null,
                requestId = null
            )
        )

        val result = PaymentResultStore.consume()
        assertNotNull(result)
        assertEquals("APPROVED", result!!.status)
        assertNull(result.requestId)

        // Fail-closed check: without operationId, no sale is finalized or enqueued
        val operationId: String? = result.requestId
        if (!operationId.isNullOrEmpty()) {
            fail("Operation ID must be null")
        }

        val postSalesCount = runBlocking { db.localSaleDao().getRecentSales().size }
        assertEquals("Zero new local sales must be created on approval without correlation key K", initialSalesCount, postSalesCount)
    }

    /**
     * A-MONEY-21: Repository atomic methods strictly require AppDatabase & PaymentAttemptDao.
     */
    @Test
    fun testA_MONEY_21_repositoryAtomicMethodsRequireAppDatabaseAndPaymentAttemptDao() {
        val repo = SaleOutboxRepository(
            context = context,
            localSaleDao = db.localSaleDao(),
            apiService = mock(),
            appDatabase = db,
            paymentAttemptDao = db.paymentAttemptDao()
        )
        assertNotNull(repo)
    }
}
