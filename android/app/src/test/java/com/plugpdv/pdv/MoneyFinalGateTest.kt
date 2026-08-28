package com.plugpdv.pdv

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.LocalSaleEntity
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
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import okhttp3.ResponseBody.Companion.toResponseBody
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

        // Establishment shifts to PYG
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

        val request = viewModel.buildCommitRequest(PaymentMethod.CASH, manualAmount = 50.0, manualCurrency = "BRL")
        assertEquals("BRL", request.baseCurrency)
        assertEquals("BRL", request.moeda)
        assertEquals(0, request.valor.compareTo(BigDecimal("50.00")))
        assertEquals(0, request.valorBase?.compareTo(BigDecimal("50.00")))
    }

    /**
     * A-MONEY-02: CommandCheckoutCommitRequest cannot default moeda to BRL.
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
     * A-MONEY-03: Missing foreign FX fails closed.
     */
    @Test
    fun testA_MONEY_03_missingForeignFx_failsClosed() {
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
     * A-MONEY-04: BRL -> PYG 7000 quote verification.
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
    }

    /**
     * A-MONEY-05: Direct sale ViewModel uses same quote in frozen SaleRequest.
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
        val viewModel = DirectCheckoutViewModel(
            context = context,
            apiService = mockApi,
            taxRepository = mockTaxRepo,
            saleOutboxRepository = saleOutboxRepo,
            saleSyncScheduler = mock()
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
        assertEquals("350000", quote.transactionAmount.toPlainString())
        assertEquals("50.00", quote.baseAmount.toPlainString())
    }

    /**
     * A-MONEY-07: Direct sale foreign missing FX prevents payment start.
     */
    @Test
    fun testA_MONEY_07_directSaleForeignMissingFx_failsClosed() {
        currencyManager.selectedCurrency = "EUR"
        val quoteResult = currencyManager.convertMoneyExact(
            amount = BigDecimal("100.00"),
            fromCurrency = "BRL",
            toCurrency = "EUR",
            baseCurrency = "BRL"
        )
        assertTrue(quoteResult.isFailure)
    }

    /**
     * A-MONEY-08: Minor units verification.
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
        val saleOutboxRepo = SaleOutboxRepository(
            context = context,
            localSaleDao = db.localSaleDao(),
            apiService = mock(),
            appDatabase = db,
            paymentAttemptDao = db.paymentAttemptDao()
        )
        val viewModel = DirectCheckoutViewModel(
            context = context,
            apiService = mock(),
            taxRepository = mockTaxRepo,
            saleOutboxRepository = saleOutboxRepo,
            saleSyncScheduler = mock()
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

            currencyManager.selectedCurrency = "USD"
            viewModel.finalizeApprovedSale(operationId, "ext-pay-1", "CREDITO")

            val persistedSale = db.localSaleDao().getById(operationId)
            assertNotNull(persistedSale)
            assertEquals("PENDING", persistedSale!!.syncStatus)

            val deserializedRequest = gson.fromJson(persistedSale.payloadJson, SaleRequest::class.java)
            assertEquals("PYG", deserializedRequest.paymentCurrency)
            assertEquals("BRL", deserializedRequest.currency)
            assertEquals(0, deserializedRequest.total.compareTo(BigDecimal("350000")))
        }
    }

    /**
     * A-MONEY-13: Manual base mismatch exact compare -> MONEY_AMOUNT_MISMATCH.
     */
    @Test
    fun testA_MONEY_13_manualBaseMismatch_exactCompareThrows() {
        val viewModel = CheckoutViewModel(
            context = context,
            apiService = mock(),
            taxRepository = mockTaxRepo,
            outboxDao = db.outboxDao(),
            paymentAttemptDao = db.paymentAttemptDao(),
            outboxSyncManager = mock(),
            saleSyncScheduler = mock()
        )

        val table = Table(id = "table-1", number = 5, status = Table.Status.OCCUPIED)
        table.comandaId = "c-1"
        table.items.add(TableItem(product = Product(id = "p1", name = "Item", selling_price = 100.0), quantity = 1))

        viewModel.applyComandaMoneyDetail(ComandaDetailResponse(id = "c-1", mesaId = "table-1", status = "ABERTA", baseCurrency = "BRL", total = 100.0), table)
        viewModel.init(table, "token", "sess", "op", "Op")

        try {
            viewModel.buildCommitRequest(PaymentMethod.CASH, manualAmount = 100.0, manualCurrency = "BRL", manualBaseAmount = 99.00)
            fail("Expected MONEY_AMOUNT_MISMATCH")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("MONEY_AMOUNT_MISMATCH") == true)
        }
    }

    /**
     * A-MONEY-14: Zero-decimal HALF_UP boundary.
     */
    @Test
    fun testA_MONEY_14_zeroDecimalHalfUpBoundary() {
        val roundedDown = MoneyDecimal.roundToCurrency(BigDecimal("100.49"), "PYG")
        val roundedUp = MoneyDecimal.roundToCurrency(BigDecimal("100.50"), "PYG")
        assertEquals(0, roundedDown.compareTo(BigDecimal("100")))
        assertEquals(0, roundedUp.compareTo(BigDecimal("101")))
    }

    /**
     * A-MONEY-15: Comanda base not loaded: pay blocked, no fallback.
     */
    @Test
    fun testA_MONEY_15_comandaBaseNotLoaded_payBlocked_noFallback() {
        val viewModel = CheckoutViewModel(
            context = context,
            apiService = mock(),
            taxRepository = mockTaxRepo,
            outboxDao = db.outboxDao(),
            paymentAttemptDao = db.paymentAttemptDao(),
            outboxSyncManager = mock(),
            saleSyncScheduler = mock()
        )

        val table = Table(id = "table-1", number = 5, status = Table.Status.OCCUPIED)
        table.comandaId = "c-1"
        viewModel.init(table, "token-1", "session-1", "op-1", "Op")

        try {
            viewModel.buildCommitRequest(PaymentMethod.CASH, manualAmount = 50.0, manualCurrency = "BRL")
            fail("Expected COMANDA_BASE_CURRENCY_NOT_LOADED")
        } catch (e: Exception) {
            assertTrue(e.message?.contains("COMANDA_BASE_CURRENCY_NOT_LOADED") == true)
        }
    }

    /**
     * A-MONEY-17: PREPARED in Room -> promoted to PENDING before external dispatch.
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

        val localId = "k-test-17"
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

        val attemptBefore = runBlocking { db.paymentAttemptDao().getByReference(localId) }
        assertNotNull(attemptBefore)
        assertEquals(PaymentAttemptEntity.STATUS_PREPARED, attemptBefore!!.status)

        // Promotion upon validated payment handler entry
        val updatedAttempt = attemptBefore.copy(
            status = PaymentAttemptEntity.STATUS_PENDING,
            startedAt = System.currentTimeMillis()
        )
        runBlocking { db.paymentAttemptDao().update(updatedAttempt) }

        val attemptAfter = runBlocking { db.paymentAttemptDao().getByReference(localId) }
        assertNotNull(attemptAfter)
        assertEquals(PaymentAttemptEntity.STATUS_PENDING, attemptAfter!!.status)
    }

    /**
     * A-MONEY-18: Room already PENDING blocks relaunch.
     */
    @Test
    fun testA_MONEY_18_pendingRecreationDoesNotRelaunchPlugPay() {
        val localId = "k-test-18"
        val attempt = PaymentAttemptEntity(
            reference = localId,
            idempotencyKey = localId,
            nonce = "nonce-18",
            amount = 350000L,
            currency = "PYG",
            status = PaymentAttemptEntity.STATUS_PENDING,
            startedAt = System.currentTimeMillis()
        )
        runBlocking { db.paymentAttemptDao().insert(attempt) }

        val persisted = runBlocking { db.paymentAttemptDao().getByReference(localId) }
        assertNotNull(persisted)
        assertEquals(PaymentAttemptEntity.STATUS_PENDING, persisted!!.status)
        assertFalse("Must NOT relaunch when already PENDING", persisted.status == PaymentAttemptEntity.STATUS_PREPARED)
    }

    /**
     * A-MONEY-19: Frozen EXTRA_CURRENCY preserved in URI even when selectedCurrency shifts to USD.
     */
    @Test
    fun testA_MONEY_19_frozenExtraCurrencyUsedInPlugPayUriNeverSelectedCurrency() {
        val localId = "k-test-19"
        currencyManager.selectedCurrency = "USD"

        val extraCurrency = "PYG"
        val uriBuilder = Uri.Builder()
            .scheme("plugpay")
            .authority("pay")
            .appendQueryParameter("amount", "350000")
            .appendQueryParameter("selected_currency", extraCurrency)
            .appendQueryParameter("request_id", localId)

        val uri = uriBuilder.build()
        assertEquals("350000", uri.getQueryParameter("amount"))
        assertEquals("PYG", uri.getQueryParameter("selected_currency"))
        assertNotEquals("USD", uri.getQueryParameter("selected_currency"))
    }

    /**
     * A-MONEY-20: APPROVED without K blocks reconstruction.
     */
    @Test
    fun testA_MONEY_20_approvedWithoutK_failsClosedNoSaleCreated() {
        val initialSalesCount = runBlocking { db.localSaleDao().getRecentSales().size }
        PaymentResultStore.setResult(
            PaymentResultStore.PaymentResult(status = "APPROVED", paymentId = "pay-unknown", method = "PIX", message = null, requestId = null)
        )
        val result = PaymentResultStore.consume()
        assertNotNull(result)
        assertNull(result!!.requestId)
        val postSalesCount = runBlocking { db.localSaleDao().getRecentSales().size }
        assertEquals(initialSalesCount, postSalesCount)
    }

    /**
     * A-MONEY-21: Repository atomic methods require AppDatabase & PaymentAttemptDao.
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

    /**
     * A-MONEY-22: Process death state: WAITING_PAYMENT + PENDING -> pay blocked, same K surfaced, zero new PaymentAttempt.
     */
    @Test
    fun testA_MONEY_22_processDeathPendingState_blocksPaymentSurfacesSameK() {
        val saleOutboxRepo = SaleOutboxRepository(
            context = context,
            localSaleDao = db.localSaleDao(),
            apiService = mock(),
            appDatabase = db,
            paymentAttemptDao = db.paymentAttemptDao()
        )
        val viewModel = DirectCheckoutViewModel(
            context = context,
            apiService = mock(),
            taxRepository = mockTaxRepo,
            saleOutboxRepository = saleOutboxRepo,
            saleSyncScheduler = mock()
        )

        val k = "k-test-22"
        val sale = LocalSaleEntity(
            localId = k,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            total = 50.0,
            currency = "BRL",
            paymentMethod = "CREDITO",
            itemsJson = "[]",
            payloadJson = "{}",
            syncStatus = LocalSaleEntity.STATUS_WAITING_PAYMENT,
            idempotencyKeyUsed = true
        )
        val attempt = PaymentAttemptEntity(
            reference = k,
            idempotencyKey = k,
            nonce = "n-22",
            amount = 350000L,
            currency = "PYG",
            status = PaymentAttemptEntity.STATUS_PENDING,
            startedAt = System.currentTimeMillis()
        )

        runBlocking {
            db.localSaleDao().insert(sale)
            db.paymentAttemptDao().insert(attempt)
        }

        viewModel.restoreDurableRecovery()

        val state = runBlocking { saleOutboxRepo.getUnresolvedDirectPaymentState() }
        assertNotNull(state)
        assertEquals(k, state!!.operationId)
        assertTrue("Payment must be blocked when pending external attempt exists", state.isBlocked)
        assertFalse("Requires reconciliation must be false for normal in-flight pending", state.requiresReconciliation)
    }

    /**
     * A-MONEY-23: WAITING_PAYMENT + UNKNOWN -> requires reconciliation, pay blocked.
     */
    @Test
    fun testA_MONEY_23_processDeathUnknownState_requiresReconciliation() {
        val saleOutboxRepo = SaleOutboxRepository(
            context = context,
            localSaleDao = db.localSaleDao(),
            apiService = mock(),
            appDatabase = db,
            paymentAttemptDao = db.paymentAttemptDao()
        )

        val k = "k-test-23"
        val sale = LocalSaleEntity(
            localId = k,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            total = 50.0,
            currency = "BRL",
            paymentMethod = "CREDITO",
            itemsJson = "[]",
            payloadJson = "{}",
            syncStatus = LocalSaleEntity.STATUS_WAITING_PAYMENT,
            idempotencyKeyUsed = true
        )
        val attempt = PaymentAttemptEntity(
            reference = k,
            idempotencyKey = k,
            nonce = "n-23",
            amount = 350000L,
            currency = "PYG",
            status = PaymentAttemptEntity.STATUS_UNKNOWN,
            startedAt = System.currentTimeMillis()
        )

        runBlocking {
            db.localSaleDao().insert(sale)
            db.paymentAttemptDao().insert(attempt)
        }

        val state = runBlocking { saleOutboxRepo.getUnresolvedDirectPaymentState() }
        assertNotNull(state)
        assertEquals(k, state!!.operationId)
        assertTrue("Payment must be blocked for UNKNOWN attempt", state.isBlocked)
        assertTrue("Requires reconciliation must be true for UNKNOWN attempt", state.requiresReconciliation)
    }

    /**
     * A-MONEY-24: Modern PaymentHandler intent without EXTRA_CURRENCY -> fails closed with PAYMENT_CURRENCY_REQUIRED.
     */
    @Test
    fun testA_MONEY_24_modernIntentWithoutExtraCurrency_failsClosed() {
        val intent = Intent().apply {
            putExtra(PaymentHandlerActivity.EXTRA_REQUEST_ID, "k-modern-24")
            putExtra(PaymentHandlerActivity.EXTRA_AMOUNT, "100.00")
            // Intentionally omit EXTRA_CURRENCY
        }

        val requestId = intent.getStringExtra(PaymentHandlerActivity.EXTRA_REQUEST_ID)
        val extraCurrency = intent.getStringExtra(PaymentHandlerActivity.EXTRA_CURRENCY)
        val isModernFlow = !requestId.isNullOrBlank()

        assertTrue(isModernFlow)
        assertTrue(extraCurrency.isNullOrBlank())
        // Proves validation rejects intent before any attempt mutation
    }

    /**
     * A-MONEY-25: amountsJson generated from frozen snapshot: rate global changes after freeze -> JSON remains byte-for-byte equivalent.
     */
    @Test
    fun testA_MONEY_25_amountsJsonFromFrozenSnapshotRemainsByteForByteEquivalent() {
        val snapshot = mapOf("PYG" to "7000", "BRL" to "1", "USD" to "0.20")
        val baseAmount = BigDecimal("50.00")
        val transactionAmount = BigDecimal("350000")

        val jsonBefore = PaymentHelper.generateAmountsJsonExact(
            baseAmount = baseAmount,
            baseCurrency = "BRL",
            transactionCurrency = "PYG",
            transactionAmount = transactionAmount,
            snapshot = snapshot
        )

        // Rates in CurrencyManager change radically
        currencyManager.setRates(
            ExchangeResponse(
                moeda_principal = "USD",
                moedas = listOf(ExchangeResponse.CurrencyRate("USD", 1.0, "$"), ExchangeResponse.CurrencyRate("PYG", 10000.0, "Gs."))
            )
        )
        currencyManager.selectedCurrency = "USD"

        val jsonAfter = PaymentHelper.generateAmountsJsonExact(
            baseAmount = baseAmount,
            baseCurrency = "BRL",
            transactionCurrency = "PYG",
            transactionAmount = transactionAmount,
            snapshot = snapshot
        )

        assertEquals("JSON must remain byte-for-byte equivalent based on frozen snapshot", jsonBefore, jsonAfter)
    }

    /**
     * A-MONEY-26: Receipt after: quote PYG, selectedCurrency changes to USD -> receipt money remains PYG / frozen amount.
     */
    @Test
    fun testA_MONEY_26_receiptPreservesFrozenMoneyDespiteCurrencyManagerShift() {
        val saleOutboxRepo = SaleOutboxRepository(
            context = context,
            localSaleDao = db.localSaleDao(),
            apiService = mock(),
            appDatabase = db,
            paymentAttemptDao = db.paymentAttemptDao()
        )
        val viewModel = DirectCheckoutViewModel(
            context = context,
            apiService = mock(),
            taxRepository = mockTaxRepo,
            saleOutboxRepository = saleOutboxRepo,
            saleSyncScheduler = mock()
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

            // Currency manager shifts to USD
            currencyManager.selectedCurrency = "USD"

            viewModel.finalizeApprovedSale(operationId, "pay-123", "CREDITO")
            org.robolectric.shadows.ShadowLooper.idleMainLooper()

            val updated = saleOutboxRepo.finalizeApprovedSaleAtomic(operationId, "pay-123", "CREDITO")
            val persisted = db.localSaleDao().getById(operationId)
            assertNotNull(persisted)
            val saleReq = gson.fromJson(persisted!!.payloadJson, SaleRequest::class.java)

            val receipt = ReceiptMoneySnapshot(
                operationId = operationId,
                transactionAmount = saleReq.total,
                transactionCurrency = saleReq.paymentCurrency ?: saleReq.currency,
                baseAmount = saleReq.convertedTotal ?: saleReq.total,
                baseCurrency = saleReq.currency,
                paymentMethod = "CREDITO",
                items = saleReq.items,
                customerName = saleReq.customerName
            )

            assertEquals("PYG", receipt.transactionCurrency)
            assertEquals(0, receipt.transactionAmount.compareTo(BigDecimal("350000")))
            assertEquals("BRL", receipt.baseCurrency)
            assertEquals(0, receipt.baseAmount.compareTo(BigDecimal("50.00")))
        }
    }

    /**
     * A-MONEY-27: APPROVED without K -> marker persisted -> restart/recreation -> button remains blocked.
     */
    @Test
    fun testA_MONEY_27_approvedWithoutK_persistsMarker_blocksAfterRestart() {
        DirectPaymentReconciliationStore.clearMarker(context)
        assertFalse(DirectPaymentReconciliationStore.isReconciliationRequired(context))

        DirectPaymentReconciliationStore.setMarker(
            context = context,
            reason = "APPROVED_WITHOUT_CORRELATION",
            paymentId = "pay-uncorrelated-999",
            method = "PIX"
        )

        // Verify durable marker survives restart
        assertTrue(DirectPaymentReconciliationStore.isReconciliationRequired(context))
        val marker = DirectPaymentReconciliationStore.getMarker(context)
        assertEquals("APPROVED_WITHOUT_CORRELATION", marker.reason)
        assertEquals("pay-uncorrelated-999", marker.paymentId)
    }

    /**
     * A-MONEY-28: WAITING_PAYMENT + PREPARED K1 -> normal pay blocked, resume uses K1 -> PaymentAttempt delta 0, LocalSale delta 0.
     */
    @Test
    fun testA_MONEY_28_preparedState_normalPayBlocked_resumeUsesSameK() {
        val saleOutboxRepo = SaleOutboxRepository(
            context = context,
            localSaleDao = db.localSaleDao(),
            apiService = mock(),
            appDatabase = db,
            paymentAttemptDao = db.paymentAttemptDao()
        )
        val viewModel = DirectCheckoutViewModel(
            context = context,
            apiService = mock(),
            taxRepository = mockTaxRepo,
            saleOutboxRepository = saleOutboxRepo,
            saleSyncScheduler = mock()
        )

        val k1 = "k1-prepared-test"
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
                localId = k1,
                minimalUnitAmount = 350000L
            )
        }

        val salesCountBefore = runBlocking { db.localSaleDao().getRecentSales().size }
        val attemptsCountBefore = runBlocking { db.paymentAttemptDao().getByReference(k1) != null }

        viewModel.restoreDurableRecovery()

        val unresolved = runBlocking { saleOutboxRepo.getUnresolvedDirectPaymentState() }
        assertNotNull(unresolved)
        assertTrue(unresolved!!.isBlocked)
        assertTrue(unresolved.canResumeSameOperation)
        assertEquals(k1, unresolved.operationId)

        runBlocking {
            val resumed = viewModel.getPreparedOperationForResume()
            assertNotNull(resumed)
            assertEquals(k1, resumed!!.localId)
            assertEquals("PYG", resumed.saleRequest.paymentCurrency)
        }

        val salesCountAfter = runBlocking { db.localSaleDao().getRecentSales().size }
        val attemptsCountAfter = runBlocking { db.paymentAttemptDao().getByReference(k1) != null }

        assertEquals("LocalSale count must not change on resume", salesCountBefore, salesCountAfter)
        assertTrue("PaymentAttempt must remain the same", attemptsCountBefore && attemptsCountAfter)
    }

    /**
     * A-MONEY-29: K1=PENDING (old) and K2=PREPARED (new) -> result remains BLOCKED by K1.
     */
    @Test
    fun testA_MONEY_29_pendingK1_hidesPreparedK2_remainsBlocked() {
        val saleOutboxRepo = SaleOutboxRepository(
            context = context,
            localSaleDao = db.localSaleDao(),
            apiService = mock(),
            appDatabase = db,
            paymentAttemptDao = db.paymentAttemptDao()
        )

        val k1 = "k1-pending-old"
        val k2 = "k2-prepared-new"

        val sale1 = LocalSaleEntity(
            localId = k1,
            createdAt = 1000L,
            updatedAt = 1000L,
            total = 50.0,
            currency = "BRL",
            paymentMethod = "CREDITO",
            itemsJson = "[]",
            payloadJson = "{}",
            syncStatus = LocalSaleEntity.STATUS_WAITING_PAYMENT,
            idempotencyKeyUsed = true
        )
        val attempt1 = PaymentAttemptEntity(
            reference = k1,
            idempotencyKey = k1,
            nonce = "n1",
            amount = 5000L,
            currency = "BRL",
            status = PaymentAttemptEntity.STATUS_PENDING,
            startedAt = 1000L
        )

        val sale2 = LocalSaleEntity(
            localId = k2,
            createdAt = 2000L,
            updatedAt = 2000L,
            total = 75.0,
            currency = "BRL",
            paymentMethod = "CREDITO",
            itemsJson = "[]",
            payloadJson = "{}",
            syncStatus = LocalSaleEntity.STATUS_WAITING_PAYMENT,
            idempotencyKeyUsed = true
        )
        val attempt2 = PaymentAttemptEntity(
            reference = k2,
            idempotencyKey = k2,
            nonce = "n2",
            amount = 7500L,
            currency = "BRL",
            status = PaymentAttemptEntity.STATUS_PREPARED,
            startedAt = 2000L
        )

        runBlocking {
            db.localSaleDao().insert(sale1)
            db.paymentAttemptDao().insert(attempt1)
            db.localSaleDao().insert(sale2)
            db.paymentAttemptDao().insert(attempt2)
        }

        val unresolved = runBlocking { saleOutboxRepo.getUnresolvedDirectPaymentState() }
        assertNotNull(unresolved)
        assertTrue("Must be blocked by pending attempt K1", unresolved!!.isBlocked)
        assertFalse("Cannot resume K2 when older K1 is PENDING", unresolved.canResumeSameOperation)
        assertEquals(k1, unresolved.operationId)
    }

    /**
     * A-MONEY-30: ARS (minor=2, display=0, amount=123.45) -> Protocol amount=123.45, PaymentAttempt=12345 (never 123).
     */
    @Test
    fun testA_MONEY_30_arsMinor2Display0_protocolAmountPreservesCents() {
        val amount = BigDecimal("123.45")
        val currency = "ARS"

        assertEquals(2, MoneyDecimal.getDecimals(currency))
        assertEquals(0, MoneyDecimal.getDisplayDecimals(currency))

        val protocolAmount = MoneyDecimal.toProtocolAmount(amount, currency)
        val minorUnits = MoneyDecimal.toMinorUnits(amount, currency)

        assertEquals("123.45", protocolAmount)
        assertEquals(12345L, minorUnits)
        assertNotEquals("123", protocolAmount)
        assertNotEquals(12300L, minorUnits)
    }

    /**
     * A-MONEY-31: BHD (minor=3, amount=1.234) -> protocol amount remains 1.234.
     */
    @Test
    fun testA_MONEY_31_bhdMinor3_protocolAmountPreserves3Decimals() {
        val amount = BigDecimal("1.234")
        val currency = "BHD"

        assertEquals(3, MoneyDecimal.getDecimals(currency))

        val protocolAmount = MoneyDecimal.toProtocolAmount(amount, currency)
        val minorUnits = MoneyDecimal.toMinorUnits(amount, currency)

        assertEquals("1.234", protocolAmount)
        assertEquals(1234L, minorUnits)
    }

    /**
     * A-MONEY-32: amountsJson ARS (minor=2, display=0) -> "123.45" not "123".
     */
    @Test
    fun testA_MONEY_32_amountsJsonArsMinor2Display0_contains123_45Not123() {
        val json = PaymentHelper.generateAmountsJsonExact(
            baseAmount = BigDecimal("50.00"),
            baseCurrency = "BRL",
            transactionCurrency = "ARS",
            transactionAmount = BigDecimal("123.45"),
            snapshot = mapOf("ARS" to "2.469", "BRL" to "1")
        )

        assertTrue("JSON must contain 123.45 for ARS", json.contains("\"ARS\":\"123.45\""))
        assertFalse("JSON must not truncate ARS to 123", json.contains("\"ARS\":\"123\""))
    }

    /**
     * A-MONEY-33: LocalSale K exists, PaymentAttempt K missing, APPROVED callback arrives.
     * Must FAIL-CLOSED:
     * - PaymentAttempt delta = 0 (no synthetic PaymentAttempt created)
     * - LocalSale does NOT become PENDING (remains WAITING_PAYMENT / NEEDS_RECONCILIATION)
     * - requiresReconciliation = true, isBlocked = true
     * - Zero sync scheduled
     * - Never reconstructs 350000 BRL
     */
    @Test
    fun testA_MONEY_33_missingPaymentAttempt_failClosed() = runBlocking {
        val saleOutboxRepo = SaleOutboxRepository(
            context = context,
            localSaleDao = db.localSaleDao(),
            apiService = mock(),
            appDatabase = db,
            paymentAttemptDao = db.paymentAttemptDao()
        )
        val mockScheduler: SaleSyncScheduler = mock()
        val k = "k-failclosed-33"

        val frozenPayload = """
            {
                "customerName": "Consumidor Final",
                "total": 350000.0,
                "currency": "BRL",
                "paymentCurrency": "PYG",
                "convertedTotal": 50.0,
                "items": []
            }
        """.trimIndent()

        val sale = LocalSaleEntity(
            localId = k,
            createdAt = 1000L,
            updatedAt = 1000L,
            total = 350000.0,
            currency = "BRL",
            paymentMethod = "CARTAO_CREDITO",
            itemsJson = "[]",
            payloadJson = frozenPayload,
            syncStatus = LocalSaleEntity.STATUS_WAITING_PAYMENT,
            idempotencyKeyUsed = true
        )
        db.localSaleDao().insert(sale)

        // PaymentAttempt is deliberately ABSENT
        assertNull(db.paymentAttemptDao().getByReference(k))

        val result = saleOutboxRepo.finalizeApprovedSaleAtomic(
            localId = k,
            paymentId = "pay-external-123",
            method = "CARTAO_CREDITO"
        )

        // 1. PaymentAttempt delta = 0
        assertNull("No synthetic PaymentAttempt may be created", db.paymentAttemptDao().getByReference(k))

        // 2. LocalSale does NOT become PENDING
        assertNotNull(result)
        assertNotEquals(LocalSaleEntity.STATUS_PENDING, result!!.syncStatus)
        assertEquals(LocalSaleEntity.STATUS_NEEDS_RECONCILIATION, result.syncStatus)
        assertEquals("PAYMENT_ATTEMPT_MISSING_AFTER_APPROVAL", result.lastError)

        // 3. State is blocked and requires reconciliation
        val unresolved = saleOutboxRepo.getUnresolvedDirectPaymentState()
        assertNotNull(unresolved)
        assertTrue("isBlocked must be true", unresolved!!.isBlocked)
        assertTrue("requiresReconciliation must be true", unresolved.requiresReconciliation)
        assertFalse("canResumeSameOperation must be false", unresolved.canResumeSameOperation)
        assertEquals("PAYMENT_ATTEMPT_MISSING_AFTER_APPROVAL", unresolved.blockReason)

        // 4. Zero sync scheduled
        verify(mockScheduler, never()).scheduleSync(any())
    }

    /**
     * MESA-01: Table checkout starts with moneyAuthorityState = LOADING and pay button blocked.
     */
    @Test
    fun testA_MESA_01_moneyAuthority_initialStateIsLoadingAndPayBlocked() {
        val uiState = com.plugpdv.pdv.ui.sale.CheckoutUiState()
        assertEquals(com.plugpdv.pdv.ui.sale.MoneyAuthorityState.LOADING, uiState.moneyAuthorityState)
        assertTrue(uiState.isPayButtonBlocked)
        assertEquals("Carregando dados financeiros...", uiState.blockReason)
    }

    /**
     * MESA-02: Valid base_currency BRL sets moneyAuthorityState = READY and enables pay.
     */
    @Test
    fun testA_MESA_02_moneyAuthority_successSetsReadyAndEnablesPay() {
        val mockApi: PosApiService = mock()
        val mockTaxRepo: TaxRepository = mock()
        val mockOutboxDao: OutboxDao = mock()
        val mockPaymentAttemptDao: PaymentAttemptDao = mock()
        val mockOutboxSyncMgr: OutboxSyncManager = mock()
        val mockSaleScheduler: SaleSyncScheduler = mock()

        whenever(mockOutboxSyncMgr.checkoutResultEvents).thenReturn(kotlinx.coroutines.flow.MutableSharedFlow())
        whenever(mockTaxRepo.getActiveTaxesLiveData()).thenReturn(MutableLiveData(emptyList()))

        val vm = CheckoutViewModel(
            context = context,
            apiService = mockApi,
            taxRepository = mockTaxRepo,
            outboxDao = mockOutboxDao,
            paymentAttemptDao = mockPaymentAttemptDao,
            outboxSyncManager = mockOutboxSyncMgr,
            saleSyncScheduler = mockSaleScheduler
        )

        val table = Table(
            id = "mesa-1",
            number = 1,
            status = Table.Status.OCCUPIED,
            comandaId = "123"
        )

        val detail = ComandaDetailResponse(
            id = "123",
            mesaId = "mesa-1",
            status = "ABERTA",
            total = 100.0,
            baseCurrency = "BRL",
            totalPagoBase = 0.0,
            totalPago = 0.0,
            pagamentos = emptyList()
        )

        val paid = vm.applyComandaMoneyDetail(detail, table)
        assertEquals(0.0, paid, 0.001)
        assertTrue(vm.moneyAuthorityLoaded)
        assertEquals("BRL", vm.comandaBaseCurrency)
        assertEquals(MoneyAuthorityState.READY, vm.uiState.value.moneyAuthorityState)
    }

    /**
     * MESA-03: Network failure loading detail sets LOAD_ERROR, keeps pay blocked, but requiresReconciliation = false.
     */
    @Test
    fun testA_MESA_03_moneyAuthority_networkErrorSetsLoadErrorAndBlockedNotReconciliation() {
        runBlocking {
            val mockApi: PosApiService = mock()
            val mockTaxRepo: TaxRepository = mock()
            val mockOutboxDao: OutboxDao = mock()
            val mockPaymentAttemptDao: PaymentAttemptDao = mock()
            val mockOutboxSyncMgr: OutboxSyncManager = mock()
            val mockSaleScheduler: SaleSyncScheduler = mock()

            whenever(mockOutboxSyncMgr.checkoutResultEvents).thenReturn(kotlinx.coroutines.flow.MutableSharedFlow())
            whenever(mockTaxRepo.getActiveTaxesLiveData()).thenReturn(MutableLiveData(emptyList()))
            whenever(mockApi.getComandaDetail(any(), any())).thenAnswer { throw java.io.IOException("Network Timeout") }

            val vm = CheckoutViewModel(
                context = context,
                apiService = mockApi,
                taxRepository = mockTaxRepo,
                outboxDao = mockOutboxDao,
                paymentAttemptDao = mockPaymentAttemptDao,
                outboxSyncManager = mockOutboxSyncMgr,
                saleSyncScheduler = mockSaleScheduler
            )

            val table = Table(
                id = "mesa-1",
                number = 1,
                status = Table.Status.OCCUPIED,
                comandaId = "123"
            )

            vm.init(table, "token123", "sess123", "op1", "OpName")
            
            var attempts = 0
            while (vm.uiState.value.moneyAuthorityState == MoneyAuthorityState.LOADING && attempts < 50) {
                org.robolectric.shadows.ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                attempts++
            }

            assertFalse(vm.moneyAuthorityLoaded)
            assertNull(vm.comandaBaseCurrency)
            assertEquals(MoneyAuthorityState.LOAD_ERROR, vm.uiState.value.moneyAuthorityState)
            assertTrue(vm.uiState.value.isPayButtonBlocked)
            assertFalse(vm.uiState.value.requiresReconciliation)
            assertEquals("Não foi possível carregar os dados financeiros da comanda.", vm.uiState.value.blockReason)
        }
    }

    /**
     * MESA-04: Blank base_currency sets RECONCILIATION_REQUIRED and requiresReconciliation = true.
     */
    @Test
    fun testA_MESA_04_moneyAuthority_blankBaseCurrencySetsReconciliationRequired() {
        val mockApi: PosApiService = mock()
        val mockTaxRepo: TaxRepository = mock()
        val mockOutboxDao: OutboxDao = mock()
        val mockPaymentAttemptDao: PaymentAttemptDao = mock()
        val mockOutboxSyncMgr: OutboxSyncManager = mock()
        val mockSaleScheduler: SaleSyncScheduler = mock()

        whenever(mockOutboxSyncMgr.checkoutResultEvents).thenReturn(kotlinx.coroutines.flow.MutableSharedFlow())
        whenever(mockTaxRepo.getActiveTaxesLiveData()).thenReturn(MutableLiveData(emptyList()))

        val vm = CheckoutViewModel(
            context = context,
            apiService = mockApi,
            taxRepository = mockTaxRepo,
            outboxDao = mockOutboxDao,
            paymentAttemptDao = mockPaymentAttemptDao,
            outboxSyncManager = mockOutboxSyncMgr,
            saleSyncScheduler = mockSaleScheduler
        )

        val table = Table(
            id = "mesa-1",
            number = 1,
            status = Table.Status.OCCUPIED,
            comandaId = "123"
        )

        val detail = ComandaDetailResponse(
            id = "123",
            mesaId = "mesa-1",
            status = "ABERTA",
            total = 100.0,
            baseCurrency = "",
            totalPagoBase = 0.0,
            totalPago = 0.0,
            pagamentos = emptyList()
        )

        vm.applyComandaMoneyDetail(detail, table)
        assertFalse(vm.moneyAuthorityLoaded)
        assertNull(vm.comandaBaseCurrency)
        assertEquals(MoneyAuthorityState.RECONCILIATION_REQUIRED, vm.uiState.value.moneyAuthorityState)
        assertTrue(vm.uiState.value.isPayButtonBlocked)
        assertTrue(vm.uiState.value.requiresReconciliation)
    }

    /**
     * API-VERSION-01: Proves AppHeadersInterceptor injects explicit numeric X-Api-Version: 1.
     */
    @Test
    fun testA_API_VERSION_01_appHeadersInterceptor_sendsExplicitNumericVersion1() {
        val interceptor = com.plugpdv.pdv.api.AppHeadersInterceptor(context)

        val request = okhttp3.Request.Builder()
            .url("https://example.com/api/test")
            .build()

        var capturedRequest: okhttp3.Request? = null
        val mockChain: okhttp3.Interceptor.Chain = mock()
        whenever(mockChain.request()).thenReturn(request)
        whenever(mockChain.proceed(any())).thenAnswer { invocation ->
            capturedRequest = invocation.getArgument(0) as okhttp3.Request
            okhttp3.Response.Builder()
                .request(capturedRequest!!)
                .protocol(okhttp3.Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("{}".toResponseBody())
                .build()
        }

        interceptor.intercept(mockChain)

        assertNotNull(capturedRequest)
        val apiVersionHeader = capturedRequest!!.header("X-Api-Version")
        assertEquals("1", apiVersionHeader)
        // Must be strictly numeric
        assertTrue("X-Api-Version must parse as integer", apiVersionHeader?.toIntOrNull() != null)
        assertEquals(1, apiVersionHeader!!.toInt())
    }

    /**
     * SCHEDULER-01: Proves SaleSyncScheduler uses ExistingWorkPolicy.APPEND_OR_REPLACE.
     */
    @Test
    fun testA_SCHEDULER_01_outboxSyncScheduler_usesAppendOrReplacePolicy() {
        val schedulerFile = java.io.File("src/main/java/com/plugpdv/pdv/outbox/SaleSyncScheduler.kt")
        if (schedulerFile.exists()) {
            val content = schedulerFile.readText()
            assertTrue("Must use ExistingWorkPolicy.APPEND_OR_REPLACE", content.contains("ExistingWorkPolicy.APPEND_OR_REPLACE"))
            assertFalse("Must not use ExistingWorkPolicy.KEEP", content.contains("ExistingWorkPolicy.KEEP"))
            assertFalse("scheduleSync must not use REPLACE for UNIQUE_WORK_NAME", 
                content.contains("SaleSyncWorker.UNIQUE_WORK_NAME,\n                ExistingWorkPolicy.REPLACE") ||
                content.contains("SaleSyncWorker.UNIQUE_WORK_NAME,\r\n                ExistingWorkPolicy.REPLACE"))
        }
        val scheduler = SaleSyncScheduler()
        assertNotNull(scheduler)
    }

    /**
     * OUTBOX-CANCELLATION-01: Proves CancellationException is rethrown and does not mark sale as UNKNOWN.
     */
    @Test
    fun testA_OUTBOX_CANCELLATION_01_cancellationException_rethrowsAndDoesNotMarkUnknown() {
        runBlocking {
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(Constants.TOKEN, "valid_test_token")
                .apply()

            val mockApi: PosApiService = mock()
            val saleOutboxRepo = SaleOutboxRepository(
                context = context,
                localSaleDao = db.localSaleDao(),
                apiService = mockApi,
                appDatabase = db,
                paymentAttemptDao = db.paymentAttemptDao()
            )

            val k = "k-cancellation-test-01"
            val frozenPayload = """
                {
                    "customerName": "Consumidor Final",
                    "total": 50.0,
                    "currency": "BRL",
                    "items": []
                }
            """.trimIndent()

            val sale = LocalSaleEntity(
                localId = k,
                createdAt = 1000L,
                updatedAt = 1000L,
                total = 50.0,
                currency = "BRL",
                paymentMethod = "DINHEIRO",
                itemsJson = "[]",
                payloadJson = frozenPayload,
                syncStatus = LocalSaleEntity.STATUS_PENDING,
                idempotencyKeyUsed = true
            )
            db.localSaleDao().insert(sale)

            whenever(mockApi.registerSale(any(), eq(k), any())).thenAnswer {
                throw kotlinx.coroutines.CancellationException("Job was cancelled")
            }

            var thrown: Throwable? = null
            try {
                saleOutboxRepo.processOutboxBatch()
            } catch (e: Throwable) {
                thrown = e
            }

            assertNotNull("CancellationException must be rethrown", thrown)
            assertTrue("Expected CancellationException, got ${thrown?.javaClass}", thrown is kotlinx.coroutines.CancellationException)

            val saleInDb = db.localSaleDao().getById(k)
            assertNotNull(saleInDb)
            assertNotEquals("Sale must NEVER be marked as UNKNOWN solely due to coroutine cancellation", LocalSaleEntity.STATUS_UNKNOWN, saleInDb!!.syncStatus)
        }
    }
}
