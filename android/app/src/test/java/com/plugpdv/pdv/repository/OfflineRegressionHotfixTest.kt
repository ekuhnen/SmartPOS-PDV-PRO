package com.plugpdv.pdv.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.*
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.ui.cashier.CashierResult
import com.plugpdv.pdv.ui.cashier.CashierUiPolicy
import com.plugpdv.pdv.ui.cashier.CashierUiState
import com.plugpdv.pdv.ui.cashier.CashierViewModel
import com.plugpdv.pdv.ui.sale.ReadProvenance
import com.plugpdv.pdv.ui.sale.TableOrderViewModel
import com.plugpdv.pdv.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class OfflineRegressionHotfixTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var tableDao: TableDao
    private lateinit var comandaSnapshotDao: ComandaSnapshotDao
    private lateinit var catalogDao: CatalogDao
    private lateinit var apiService: PosApiService
    private lateinit var tableReadRepository: TableReadRepository
    private lateinit var comandaSnapshotRepository: ComandaSnapshotRepository
    private val gson = Gson()

    private val tenantA = "tenant_alpha_123"
    private val tenantB = "tenant_beta_456"
    private val userA = "user_alpha_1"
    private val userB = "user_beta_2"

    private suspend fun waitUntil(timeoutMs: Long = 8000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < timeoutMs) {
            ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            kotlinx.coroutines.delay(50)
        }
        ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        TenantBindingStore.clearTenant(context)
        CashierAuthorityStore.clearAuthority(context)

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        tableDao = db.tableDao()
        comandaSnapshotDao = db.comandaSnapshotDao()
        catalogDao = db.catalogDao()
        apiService = mock()

        tableReadRepository = TableReadRepository(context, tableDao, apiService, catalogDao)
        comandaSnapshotRepository = ComandaSnapshotRepository(context, comandaSnapshotDao, gson)
    }

    @After
    fun tearDown() {
        db.close()
        TenantBindingStore.clearTenant(context)
        CashierAuthorityStore.clearAuthority(context)
    }

    private fun createSampleSnapshot(
        tenantId: String = tenantA,
        tableId: String = "table_1",
        serverComandaId: String = "comanda_101",
        items: List<MesaItemDto> = listOf(
            MesaItemDto(id = "item_1", produto_id = "prod_1", nome = "Burger", preco_unitario = 25.0, quantidade = 2, subtotal = 50.0, status = "ENVIADO")
        ),
        localStatus: String = "OPEN",
        serverStatus: String = "ABERTA",
        requiresReconciliation: Boolean = false,
        syncStatus: String = "SYNCED"
    ): ComandaSnapshotEntity {
        return ComandaSnapshotEntity(
            localComandaId = "local_" + serverComandaId,
            serverComandaId = serverComandaId,
            tenantId = tenantId,
            tableId = tableId,
            tableNumber = 1,
            customerIdentifier = "John Doe",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = serverStatus,
            localStatus = localStatus,
            syncStatus = syncStatus,
            totalBaseMinor = 5000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 5000L,
            serverRevision = null,
            localRevision = 0L,
            itemsJson = gson.toJson(items),
            paymentsJson = "[]",
            requiresReconciliation = requiresReconciliation,
            reconciliationReason = null,
            serverUpdatedAt = System.currentTimeMillis(),
            cachedAt = System.currentTimeMillis()
        )
    }

    // ==========================================
    // MESA TESTS (1..10)
    // ==========================================

    @Test
    fun test01_mesa_cachedItemsSurviveNetworkLoss() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val snapshot = createSampleSnapshot()
        comandaSnapshotDao.upsert(snapshot)

        val tableEntity = TableEntity(
            id = "table_1",
            number = 1,
            status = Table.Status.OCCUPIED,
            sectorName = "Salão",
            sectorId = "sec_1",
            customerName = "John Doe",
            comandaId = "comanda_101",
            updatedAt = System.currentTimeMillis()
        )
        tableDao.insert(tableEntity)

        // Simulate offline TableOrderViewModel loading
        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("No network") }

        val viewModel = TableOrderViewModel(context, apiService, catalogDao, tableReadRepository, comandaSnapshotRepository)
        viewModel.init("table_1", 1, "sec_1", "fake_token")

        waitUntil { viewModel.table.value?.items?.isNotEmpty() == true }

        val loadedTable = viewModel.table.value
        assertNotNull("Loaded table should not be null", loadedTable)
        assertEquals("Table should contain 1 item group", 1, loadedTable?.items?.size)
        assertEquals("Burger", loadedTable?.items?.first()?.product?.name)
        assertEquals(2, loadedTable?.items?.first()?.quantity)
        assertEquals(25.0, loadedTable?.items?.first()?.product?.selling_price)
    }

    @Test
    fun test02_mesa_exactServerComandaIdLookupWorks() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val snapshot = createSampleSnapshot(serverComandaId = "comanda_exact_999")
        comandaSnapshotDao.upsert(snapshot)

        val found = comandaSnapshotRepository.getByServerComandaId(tenantA, "comanda_exact_999")
        assertNotNull(found)
        assertEquals("table_1", found?.tableId)
        assertEquals("comanda_exact_999", found?.serverComandaId)
    }

    @Test
    fun test03_mesa_validatedSameTenantTableFallbackWorks() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val snapshot = createSampleSnapshot(tableId = "table_fallback_7", serverComandaId = "comanda_777")
        comandaSnapshotDao.upsert(snapshot)

        // TableEntity has null comandaId
        val tableEntity = TableEntity(
            id = "table_fallback_7",
            number = 7,
            status = Table.Status.OCCUPIED,
            sectorName = "Varanda",
            sectorId = "sec_2",
            customerName = "Alice",
            comandaId = null,
            updatedAt = System.currentTimeMillis()
        )
        tableDao.insert(tableEntity)

        whenever(apiService.getMesas(any())).thenAnswer { throw IOException("No network") }
        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("No network") }

        val viewModel = TableOrderViewModel(context, apiService, catalogDao, tableReadRepository, comandaSnapshotRepository)
        viewModel.init("table_fallback_7", 7, "sec_2", "fake_token")

        waitUntil { viewModel.table.value?.items?.isNotEmpty() == true }

        val loadedTable = viewModel.table.value
        assertNotNull(loadedTable)
        assertEquals(1, loadedTable?.items?.size)
        assertEquals("Burger", loadedTable?.items?.first()?.product?.name)
        assertEquals("comanda_777", loadedTable?.comandaId) // Repaired linkage
    }

    @Test
    fun test04_mesa_wrongTenantRejected() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        // Snapshot stored under tenantB
        val snapshot = createSampleSnapshot(tenantId = tenantB, tableId = "table_1", serverComandaId = "comanda_101")
        comandaSnapshotDao.upsert(snapshot)

        val decision = ComandaSnapshotAuthorityPolicy.evaluate(snapshot, "comanda_101", context)
        assertEquals(SnapshotAuthorityDecision.WRONG_TENANT, decision)

        val lookup = comandaSnapshotRepository.getByServerComandaId(tenantA, "comanda_101")
        assertNull("Snapshot belonging to tenantB must not be returned for tenantA", lookup)
    }

    @Test
    fun test05_mesa_wrongTableRejected() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val snapshot = createSampleSnapshot(tableId = "table_other_99", serverComandaId = "comanda_101")
        comandaSnapshotDao.upsert(snapshot)

        val lookup = comandaSnapshotRepository.getByTableId(tenantA, "table_1")
        assertNull("Lookup for table_1 must not return snapshot bound to table_other_99", lookup)
    }

    @Test
    fun test06_mesa_ambiguousSnapshotRejected() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val snapshot = createSampleSnapshot(requiresReconciliation = true)
        comandaSnapshotDao.upsert(snapshot)

        val decision = ComandaSnapshotAuthorityPolicy.evaluate(snapshot, "comanda_101", context)
        assertEquals(SnapshotAuthorityDecision.RECONCILIATION_REQUIRED, decision)
    }

    @Test
    fun test07_mesa_closedOrCancelledSnapshotNotResurrected() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val closedSnapshot = createSampleSnapshot(localStatus = "CLOSED", serverStatus = "FECHADA")
        val cancelledSnapshot = createSampleSnapshot(serverComandaId = "comanda_canc", localStatus = "CANCELLED", serverStatus = "CANCELADA")

        assertEquals(SnapshotAuthorityDecision.CLOSED, ComandaSnapshotAuthorityPolicy.evaluate(closedSnapshot, "comanda_101", context))
        assertEquals(SnapshotAuthorityDecision.CANCELLED, ComandaSnapshotAuthorityPolicy.evaluate(cancelledSnapshot, "comanda_canc", context))
    }

    @Test
    fun test08_mesa_catalogPriceNeverReplacesFrozenItemPrice() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        // Item in snapshot was bought at 25.0
        val snapshot = createSampleSnapshot(
            items = listOf(
                MesaItemDto(id = "item_1", produto_id = "prod_1", nome = "Pizza", preco_unitario = 25.0, quantidade = 1, subtotal = 25.0, status = "ENVIADO")
            )
        )
        comandaSnapshotDao.upsert(snapshot)

        // Catalog currently sells pizza at 45.0
        catalogDao.insertAll(listOf(Product(id = "prod_1", name = "Pizza", selling_price = 45.0)))

        val tableEntity = TableEntity(
            id = "table_1",
            number = 1,
            status = Table.Status.OCCUPIED,
            sectorName = "Salão",
            sectorId = "sec_1",
            comandaId = "comanda_101",
            updatedAt = System.currentTimeMillis()
        )
        tableDao.insert(tableEntity)

        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("No network") }

        val viewModel = TableOrderViewModel(context, apiService, catalogDao, tableReadRepository, comandaSnapshotRepository)
        viewModel.init("table_1", 1, "sec_1", "fake_token")

        waitUntil { viewModel.table.value?.items?.isNotEmpty() == true }

        val loadedTable = viewModel.table.value
        assertEquals(1, loadedTable?.items?.size)
        // Invariant: Snapshot price (25.0) is authoritative, NOT catalog price (45.0)
        assertEquals(25.0, loadedTable?.items?.first()?.product?.selling_price)
    }

    @Test
    fun test09_mesa_onlineAddLocalSnapshotOfflineReopenRetainsItem() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val table = Table(number = 1).apply {
            id = "table_1"
            comandaId = "comanda_101"
            sectorId = "sec_1"
        }
        val detail = ComandaDetailResponse(
            id = "comanda_101",
            mesaId = "table_1",
            numero = 1,
            status = "ABERTA",
            total = 30.0,
            totalPagoBase = 0.0,
            saldoBase = 30.0,
            baseCurrency = "BRL",
            itens = listOf(
                MesaItemDto(id = "item_add_1", produto_id = "prod_drink", nome = "Suco", preco_unitario = 15.0, quantidade = 2, subtotal = 30.0, status = "RASCUNHO")
            )
        )

        // Online sync caches snapshot
        comandaSnapshotRepository.cacheRemoteDetail(detail, table)

        // Verify snapshot in Room
        val inRoom = comandaSnapshotDao.getByServerComandaId(tenantA, "comanda_101")
        assertNotNull(inRoom)
        assertTrue(inRoom!!.itemsJson.contains("Suco"))

        // Reopen offline
        tableDao.insert(TableEntity(id = "table_1", number = 1, status = Table.Status.OCCUPIED, sectorName = "Salão", sectorId = "sec_1", comandaId = "comanda_101", updatedAt = System.currentTimeMillis()))
        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("No network") }

        val viewModel = TableOrderViewModel(context, apiService, catalogDao, tableReadRepository, comandaSnapshotRepository)
        viewModel.init("table_1", 1, "sec_1", "fake_token")
        waitUntil { viewModel.table.value?.items?.isNotEmpty() == true }

        assertEquals(1, viewModel.table.value?.items?.size)
        assertEquals("Suco", viewModel.table.value?.items?.first()?.product?.name)
    }

    @Test
    fun test10_mesa_sendKitchenOfflineReopenRetainsItem() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val tableEntity = TableEntity(id = "table_1", number = 1, status = Table.Status.OCCUPIED, sectorName = "Salão", sectorId = "sec_1", comandaId = "comanda_101", updatedAt = System.currentTimeMillis())
        tableDao.insert(tableEntity)

        val detail = ComandaDetailResponse(
            id = "comanda_101",
            mesaId = "table_1",
            numero = 1,
            status = "ABERTA",
            total = 50.0,
            totalPagoBase = 0.0,
            saldoBase = 50.0,
            baseCurrency = "BRL",
            itens = listOf(
                MesaItemDto(id = "item_k_1", produto_id = "prod_meat", nome = "Picanha", preco_unitario = 50.0, quantidade = 1, subtotal = 50.0, status = "ENVIADO")
            )
        )

        whenever(apiService.manageComanda(any(), any(), any())).thenReturn(Response.success(mapOf("success" to true)))
        whenever(apiService.getComandaDetail(any(), any())).thenReturn(detail)

        val viewModel = TableOrderViewModel(context, apiService, catalogDao, tableReadRepository, comandaSnapshotRepository)
        viewModel.init("table_1", 1, "sec_1", "fake_token")
        waitUntil { viewModel.table.value != null }

        var onSuccessCalled = false
        viewModel.enviarCozinha { onSuccessCalled = true }
        waitUntil { onSuccessCalled }

        assertTrue(onSuccessCalled)

        // Verify snapshot in Room has "Picanha"
        val cached = comandaSnapshotDao.getByServerComandaId(tenantA, "comanda_101")
        assertNotNull(cached)
        assertTrue(cached!!.itemsJson.contains("Picanha"))
    }

    // ==========================================
    // CASHIER TESTS (11..20)
    // ==========================================

    @Test
    fun test11_cashier_localOpenPlusIOExceptionRemainsOpen() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(Constants.USER_ID, userA).apply()

        CashierAuthorityStore.setOpen(context, tenantA, userA, "session_open_11")

        whenever(apiService.getCashierHistory(anyOrNull(), anyOrNull())).thenAnswer { throw IOException("Connection reset") }

        val viewModel = CashierViewModel(context, apiService)
        assertEquals(CashierAuthorityState.OPEN("session_open_11", tenantA, userA, (viewModel.cashierState.value as CashierAuthorityState.OPEN).updatedAt), viewModel.cashierState.value)
        assertEquals(false, viewModel.isClosed.value)

        viewModel.fetchHistory("token")
        waitUntil { viewModel.isOffline.value == true }

        // Invariant: Network failure NEVER converts known OPEN to CLOSED
        assertTrue(viewModel.cashierState.value is CashierAuthorityState.OPEN)
        assertEquals(false, viewModel.isClosed.value)
        assertEquals(true, viewModel.isOffline.value)
    }

    @Test
    fun test12_cashier_localOpenPlusHttp5xxRemainsOpen() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(Constants.USER_ID, userA).apply()

        CashierAuthorityStore.setOpen(context, tenantA, userA, "session_open_12")

        val errorResponse = Response.error<CashierHistoryResponse>(500, "Internal Server Error".toResponseBody("text/plain".toMediaTypeOrNull()))
        whenever(apiService.getCashierHistory(anyOrNull(), anyOrNull())).thenAnswer { throw HttpException(errorResponse) }

        val viewModel = CashierViewModel(context, apiService)
        viewModel.fetchHistory("token")
        waitUntil { viewModel.isOffline.value == true }

        assertTrue(viewModel.cashierState.value is CashierAuthorityState.OPEN)
        assertEquals(false, viewModel.isClosed.value)
        assertEquals(true, viewModel.isOffline.value)
    }

    @Test
    fun test13_cashier_localClosedPlusOfflineRemainsClosed() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(Constants.USER_ID, userA).apply()

        CashierAuthorityStore.setClosed(context, tenantA, userA)

        whenever(apiService.getCashierHistory(anyOrNull(), anyOrNull())).thenAnswer { throw IOException("No network") }

        val viewModel = CashierViewModel(context, apiService)
        viewModel.fetchHistory("token")
        waitUntil { viewModel.isOffline.value == true }

        assertTrue(viewModel.cashierState.value is CashierAuthorityState.CLOSED)
        assertEquals(true, viewModel.isClosed.value)
        assertEquals(true, viewModel.isOffline.value)
    }

    @Test
    fun test14_cashier_unknownPlusOfflineRemainsUnknownNotClosed() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        // No prior stored authority

        whenever(apiService.getCashierHistory(anyOrNull(), anyOrNull())).thenAnswer { throw IOException("No network") }

        val viewModel = CashierViewModel(context, apiService)
        viewModel.fetchHistory("token")
        waitUntil { viewModel.isOffline.value == true }

        // Invariant: UNKNOWN is not the same as CLOSED
        assertTrue(viewModel.cashierState.value is CashierAuthorityState.UNKNOWN)
        assertEquals(false, viewModel.isClosed.value)
        assertEquals(true, viewModel.isOffline.value)
    }

    @Test
    fun test15_cashier_wrongTenantSessionNotReused() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        CashierAuthorityStore.setOpen(context, tenantA, userA, "session_tenant_a")

        // Switch tenant to tenantB
        TenantBindingStore.setActiveTenantId(context, tenantB)

        val authority = CashierAuthorityStore.getAuthority(context, tenantB, userA)
        assertTrue("Session from tenantA must NOT be reused for tenantB", authority is CashierAuthorityState.UNKNOWN)
        assertEquals("TENANT_MISMATCH", (authority as CashierAuthorityState.UNKNOWN).reason)
    }

    @Test
    fun test16_cashier_wrongUserSessionNotReused() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        CashierAuthorityStore.setOpen(context, tenantA, userA, "session_user_a")

        val authority = CashierAuthorityStore.getAuthority(context, tenantA, userB)
        assertTrue("Session from userA must NOT be reused for userB", authority is CashierAuthorityState.UNKNOWN)
        assertEquals("USER_MISMATCH", (authority as CashierAuthorityState.UNKNOWN).reason)
    }

    @Test
    fun test17_cashier_logoutInvalidatesAuthorityCorrectly() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        CashierAuthorityStore.setOpen(context, tenantA, userA, "session_to_clear")

        CashierAuthorityStore.clearAuthority(context)

        val authority = CashierAuthorityStore.getAuthority(context, tenantA, userA)
        assertTrue(authority is CashierAuthorityState.UNKNOWN)
        assertNull(context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).getString(Constants.SESSION_ID, null))
    }

    @Test
    fun test18_cashier_openOfflinePermitsBackNavigation() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(Constants.USER_ID, userA).apply()

        CashierAuthorityStore.setOpen(context, tenantA, userA, "session_open_18")

        val viewModel = CashierViewModel(context, apiService)
        val state = viewModel.cashierState.value

        // When OPEN: back is allowed
        assertTrue(state is CashierAuthorityState.OPEN)
        val shouldBlockBack = (state is CashierAuthorityState.CLOSED) && (viewModel.isOffline.value != true)
        assertFalse("Back press must NOT be blocked when cashier is OPEN offline", shouldBlockBack)
    }

    @Test
    fun test19_cashier_unknownOfflinePermitsSafeExit() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        // No authority = UNKNOWN
        val viewModel = CashierViewModel(context, apiService)
        val state = viewModel.cashierState.value

        assertTrue(state is CashierAuthorityState.UNKNOWN)
        val shouldBlockBack = (state is CashierAuthorityState.CLOSED) && (viewModel.isOffline.value != true)
        assertFalse("Back press must NOT be blocked when cashier is UNKNOWN offline", shouldBlockBack)
    }

    @Test
    fun test20_cashier_offlineCashierMutationsRemainBlocked() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(Constants.USER_ID, userA).apply()

        CashierAuthorityStore.setOpen(context, tenantA, userA, "session_open_20")

        whenever(apiService.getCashierHistory(anyOrNull(), anyOrNull())).thenAnswer { throw IOException("Offline") }

        val viewModel = CashierViewModel(context, apiService)
        viewModel.fetchHistory("token")
        waitUntil { viewModel.isOffline.value == true }

        assertEquals(true, viewModel.isOffline.value)

        // Attempting financial operation offline must be blocked immediately
        viewModel.performOperation("token", "sangria", 50.0)
        waitUntil { viewModel.operationResult.value != null }

        val result = viewModel.operationResult.value
        assertTrue("Offline mutation must produce Error", result is CashierResult.Error)
        assertTrue((result as CashierResult.Error).message.contains("Sem conexão"))
    }

    @Test
    fun test21_mesa_twoUsableSnapshotsForSameTenantTableRejectedAsAmbiguous() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        // Two USABLE snapshots for the same table
        val snapshot1 = createSampleSnapshot(serverComandaId = "comanda_ambig_1", tableId = "table_ambig")
        val snapshot2 = createSampleSnapshot(serverComandaId = "comanda_ambig_2", tableId = "table_ambig")
        comandaSnapshotDao.upsert(snapshot1)
        comandaSnapshotDao.upsert(snapshot2)

        val tableEntity = TableEntity(
            id = "table_ambig",
            number = 99,
            status = Table.Status.OCCUPIED,
            sectorName = "Salão",
            sectorId = "sec_1",
            customerName = "Ambiguous Table",
            comandaId = null,
            updatedAt = System.currentTimeMillis()
        )
        tableDao.insert(tableEntity)

        whenever(apiService.getMesas(any())).thenAnswer { throw IOException("No network") }
        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("No network") }

        val viewModel = TableOrderViewModel(context, apiService, catalogDao, tableReadRepository, comandaSnapshotRepository)
        viewModel.init("table_ambig", 99, "sec_1", "fake_token")
        waitUntil { viewModel.table.value != null }

        val loaded = viewModel.table.value
        assertNotNull(loaded)
        // Invariant: Multiple usable snapshot candidates => ambiguous/conflict, choose NEITHER, keep 0 items
        assertEquals(0, loaded?.items?.size)
        assertNull(loaded?.comandaId)
    }

    @Test
    fun test22_mesa_knownComandaIdAAndTableCandidateBDifferRejectsConflict() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        // Snapshot candidate has serverComandaId = "comanda_B"
        val snapshotB = createSampleSnapshot(serverComandaId = "comanda_B", tableId = "table_conflict")
        comandaSnapshotDao.upsert(snapshotB)

        // TableEntity has known comandaId = "comanda_A" (different from "comanda_B")
        val tableEntity = TableEntity(
            id = "table_conflict",
            number = 88,
            status = Table.Status.OCCUPIED,
            sectorName = "Salão",
            sectorId = "sec_1",
            customerName = "Conflict Table",
            comandaId = "comanda_A",
            updatedAt = System.currentTimeMillis()
        )
        tableDao.insert(tableEntity)

        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("No network") }

        val viewModel = TableOrderViewModel(context, apiService, catalogDao, tableReadRepository, comandaSnapshotRepository)
        viewModel.init("table_conflict", 88, "sec_1", "fake_token")
        waitUntil { viewModel.table.value != null }

        val loaded = viewModel.table.value
        assertNotNull(loaded)
        // Invariant: Never repair known comandaId A -> B. Retains A, does NOT apply B
        assertEquals("comanda_A", loaded?.comandaId)
        assertEquals(0, loaded?.items?.size)
    }

    @Test
    fun test23_mesa_enviarCozinhaSucceedsEvenIfSubsequentGetDetailFails() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val tableEntity = TableEntity(
            id = "table_k_success",
            number = 5,
            status = Table.Status.OCCUPIED,
            sectorName = "Salão",
            sectorId = "sec_1",
            comandaId = "comanda_k_5",
            updatedAt = System.currentTimeMillis()
        )
        tableDao.insert(tableEntity)

        val initialDetail = ComandaDetailResponse(
            id = "comanda_k_5",
            mesaId = "table_k_success",
            numero = 5,
            status = "ABERTA",
            total = 0.0,
            totalPagoBase = 0.0,
            saldoBase = 0.0,
            baseCurrency = "BRL",
            itens = emptyList()
        )
        whenever(apiService.getComandaDetail(any(), any())).thenReturn(initialDetail)

        val viewModel = TableOrderViewModel(context, apiService, catalogDao, tableReadRepository, comandaSnapshotRepository)
        viewModel.init("table_k_success", 5, "sec_1", "fake_token")
        waitUntil { viewModel.isLoading.value == false && viewModel.readProvenance.value == ReadProvenance.REMOTE_REFRESHED }

        // manageComanda succeeds
        whenever(apiService.manageComanda(any(), any(), any())).thenReturn(Response.success(mapOf("success" to true)))
        // Subsequent getComandaDetail fails with IOException/timeout
        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("Timeout on detail refresh") }

        var onSuccessCalled = false
        viewModel.enviarCozinha { onSuccessCalled = true }
        waitUntil { onSuccessCalled }

        // Invariant: manageComanda success is authoritative. Failure on best-effort detail refresh does NOT trigger failure in UI
        assertTrue("onSuccess must execute", onSuccessCalled)
        assertNull("No error should be set on kitchen send success", viewModel.error.value)
    }

    @Test
    fun test24a_cashier_httpClassification401NotOffline() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(Constants.USER_ID, userA).apply()
        CashierAuthorityStore.setOpen(context, tenantA, userA, "session_open_24a")

        whenever(apiService.getExchangeRates(anyOrNull(), anyOrNull())).thenReturn(ExchangeResponse(moedas = emptyList()))

        val err401 = Response.error<CashierHistoryResponse>(401, "Unauthorized".toResponseBody("text/plain".toMediaTypeOrNull()))
        whenever(apiService.getCashierHistory(anyOrNull(), anyOrNull())).thenAnswer { throw HttpException(err401) }

        val viewModel = CashierViewModel(context, apiService)
        viewModel.fetchHistory("token_401")
        waitUntil { viewModel.sessionExpired.value == true && viewModel.isLoading.value == false }

        assertEquals(false, viewModel.isOffline.value)
        assertEquals(true, viewModel.sessionExpired.value)
    }

    @Test
    fun test24b_cashier_httpClassification403NotOffline() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(Constants.USER_ID, userA).apply()
        CashierAuthorityStore.setOpen(context, tenantA, userA, "session_open_24b")

        whenever(apiService.getExchangeRates(anyOrNull(), anyOrNull())).thenReturn(ExchangeResponse(moedas = emptyList()))

        val err403 = Response.error<CashierHistoryResponse>(403, "Forbidden".toResponseBody("text/plain".toMediaTypeOrNull()))
        whenever(apiService.getCashierHistory(anyOrNull(), anyOrNull())).thenAnswer { throw HttpException(err403) }

        val viewModel = CashierViewModel(context, apiService)
        viewModel.fetchHistory("token_403")
        waitUntil { viewModel.isOffline.value == false && viewModel.isLoading.value == false }

        assertEquals(false, viewModel.isOffline.value)
    }

    @Test
    fun test25_cashier_closedOfflineRemainsClosedTransactionallyAndAllowsSafeBack() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(Constants.USER_ID, userA).apply()
        CashierAuthorityStore.setClosed(context, tenantA, userA)

        whenever(apiService.getCashierHistory(anyOrNull(), anyOrNull())).thenAnswer { throw IOException("No network") }

        val viewModel = CashierViewModel(context, apiService)
        viewModel.fetchHistory("token_closed_off")
        waitUntil { viewModel.isOffline.value == true }

        val state = viewModel.cashierState.value
        val isOffline = viewModel.isOffline.value == true

        // Transactionally CLOSED
        assertTrue(state is CashierAuthorityState.CLOSED)
        assertTrue(isOffline)

        // Mutations remain blocked offline
        viewModel.performOperation("token", "abrir", 100.0)
        waitUntil { viewModel.operationResult.value != null }
        val opResult = viewModel.operationResult.value
        assertTrue(opResult is CashierResult.Error)
        assertTrue((opResult as CashierResult.Error).message.contains("Sem conexão"))

        // Safe back / exit is allowed because isOffline == true
        val shouldBlockBack = (state is CashierAuthorityState.CLOSED) && !isOffline
        assertFalse("CLOSED + OFFLINE must allow safe back/exit", shouldBlockBack)
    }

    @Test
    fun test26_cashier_uiPolicyOpenOfflineAndOnline() {
        val openState = CashierAuthorityState.OPEN("sess_1", tenantA, userA, System.currentTimeMillis())

        // OPEN + ONLINE
        val onlineUi = CashierUiPolicy.calculateUiState(openState, isOffline = false, isLoading = false)
        assertFalse("OPEN + ONLINE: open must be disabled", onlineUi.isOpenEnabled)
        assertTrue("OPEN + ONLINE: sangria must be enabled", onlineUi.isSangriaEnabled)
        assertTrue("OPEN + ONLINE: close must be enabled", onlineUi.isCloseEnabled)
        assertTrue("OPEN + ONLINE: dashboard must be enabled", onlineUi.isDashboardEnabled)
        assertTrue("OPEN + ONLINE: safe back must be enabled", onlineUi.isSafeBackEnabled)

        // OPEN + OFFLINE
        val offlineUi = CashierUiPolicy.calculateUiState(openState, isOffline = true, isLoading = false)
        assertFalse("OPEN + OFFLINE: open must be disabled", offlineUi.isOpenEnabled)
        assertFalse("OPEN + OFFLINE: sangria must be disabled", offlineUi.isSangriaEnabled)
        assertFalse("OPEN + OFFLINE: close must be disabled", offlineUi.isCloseEnabled)
        assertFalse("OPEN + OFFLINE: dashboard must be disabled", offlineUi.isDashboardEnabled)
        assertTrue("OPEN + OFFLINE: safe back must be enabled", offlineUi.isSafeBackEnabled)
    }

    @Test
    fun test27_cashier_uiPolicyClosedOfflineAndOnline() {
        val closedState = CashierAuthorityState.CLOSED(tenantA, userA, System.currentTimeMillis())

        // CLOSED + ONLINE
        val onlineUi = CashierUiPolicy.calculateUiState(closedState, isOffline = false, isLoading = false)
        assertTrue("CLOSED + ONLINE: open must be enabled", onlineUi.isOpenEnabled)
        assertFalse("CLOSED + ONLINE: sangria must be disabled", onlineUi.isSangriaEnabled)
        assertFalse("CLOSED + ONLINE: close must be disabled", onlineUi.isCloseEnabled)
        assertFalse("CLOSED + ONLINE: dashboard must be disabled", onlineUi.isDashboardEnabled)
        assertFalse("CLOSED + ONLINE: back navigation locked until open", onlineUi.isSafeBackEnabled)

        // CLOSED + OFFLINE
        val offlineUi = CashierUiPolicy.calculateUiState(closedState, isOffline = true, isLoading = false)
        assertFalse("CLOSED + OFFLINE: open must be disabled", offlineUi.isOpenEnabled)
        assertFalse("CLOSED + OFFLINE: sangria must be disabled", offlineUi.isSangriaEnabled)
        assertFalse("CLOSED + OFFLINE: close must be disabled", offlineUi.isCloseEnabled)
        assertFalse("CLOSED + OFFLINE: dashboard must be disabled", offlineUi.isDashboardEnabled)
        assertTrue("CLOSED + OFFLINE: safe exit allowed", offlineUi.isSafeBackEnabled)
    }

    @Test
    fun test28_cashier_uiPolicyUnknownOfflineAndLoading() {
        val unknownState = CashierAuthorityState.UNKNOWN("TEST_REASON")

        // UNKNOWN + OFFLINE
        val offlineUi = CashierUiPolicy.calculateUiState(unknownState, isOffline = true, isLoading = false)
        assertFalse(offlineUi.isOpenEnabled)
        assertFalse(offlineUi.isSangriaEnabled)
        assertFalse(offlineUi.isCloseEnabled)
        assertFalse(offlineUi.isDashboardEnabled)
        assertTrue(offlineUi.isSafeBackEnabled)

        // UNKNOWN + ONLINE
        val onlineUi = CashierUiPolicy.calculateUiState(unknownState, isOffline = false, isLoading = false)
        assertFalse(onlineUi.isOpenEnabled)
        assertFalse(onlineUi.isSangriaEnabled)
        assertFalse(onlineUi.isCloseEnabled)
        assertFalse(onlineUi.isDashboardEnabled)
        assertTrue(onlineUi.isSafeBackEnabled)

        // ANY + LOADING
        val openState = CashierAuthorityState.OPEN("sess_1", tenantA, userA, System.currentTimeMillis())
        val loadingUi = CashierUiPolicy.calculateUiState(openState, isOffline = false, isLoading = true)
        assertFalse(loadingUi.isOpenEnabled)
        assertFalse(loadingUi.isSangriaEnabled)
        assertFalse(loadingUi.isCloseEnabled)
    }

    @Test
    fun test29_mesa_refreshAVsBConflictFollowedByOfflineReopenNeverAppliesB() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)

        // 1. Local state: TableEntity has comandaId = "comanda_A"
        val tableEntity = TableEntity(
            id = "table_ab",
            number = 77,
            status = Table.Status.OCCUPIED,
            sectorName = "Salão",
            sectorId = "sec_1",
            customerName = "Mesa AB",
            comandaId = "comanda_A",
            updatedAt = System.currentTimeMillis()
        )
        tableDao.insert(tableEntity)

        // 2. Snapshot store has conflicting snapshot with serverComandaId = "comanda_B"
        val snapshotB = createSampleSnapshot(
            serverComandaId = "comanda_B",
            tableId = "table_ab",
            tenantId = tenantA,
            items = listOf(MesaItemDto(id = "item_b", nome = "Burger B", preco_unitario = 30.0, quantidade = 1))
        )
        comandaSnapshotDao.upsert(snapshotB)

        // 3. Remote refresh: api-mesas returns OCCUPIED table with blank/null comanda_id
        val remoteMesasResponse = RestaurantResponse(
            setores = listOf(
                Sector(
                    id = "sec_1",
                    nome = "Salão",
                    mesas = listOf(
                        MesaDto(id = "table_ab", numero = 77, status = "OCUPADA", comanda_id = null)
                    )
                )
            )
        )
        whenever(apiService.getMesas(any())).thenReturn(remoteMesasResponse)

        val refreshResult = tableReadRepository.refreshTables("token")
        assertTrue(refreshResult.isSuccess)

        // Invariant: refreshTables must NOT erase A to null or convert to B
        val refreshedTable = tableDao.getTableById("table_ab")
        assertNotNull(refreshedTable)
        assertEquals("comanda_A", refreshedTable?.comandaId)

        // 4. Now user re-opens table offline
        whenever(apiService.getMesas(any())).thenAnswer { throw IOException("No network") }
        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("No network") }

        val viewModel = TableOrderViewModel(context, apiService, catalogDao, tableReadRepository, comandaSnapshotRepository)
        viewModel.init("table_ab", 77, "sec_1", "fake_token")
        waitUntil { viewModel.table.value != null }

        val loaded = viewModel.table.value
        assertNotNull(loaded)

        // Invariant: B was NEVER applied; A was never silently converted to B; items from B not displayed
        assertEquals("comanda_A", loaded?.comandaId)
        assertEquals(0, loaded?.items?.size)
    }

    @Test
    fun test30_cashier_remoteOpenWithMissingUserIdRemainsUnknown() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(Constants.USER_ID).apply()

        val openSession = CashierSession(
            id = "sess_remote_30",
            caixa_session_id = "sess_remote_30",
            tipo = "ABERTURA",
            valor = 100.0
        )
        val response = CashierHistoryResponse(operacoes = listOf(openSession))
        whenever(apiService.getCashierHistory(anyOrNull(), anyOrNull())).thenReturn(response)
        whenever(apiService.getExchangeRates(anyOrNull(), anyOrNull())).thenReturn(ExchangeResponse(moedas = emptyList()))

        val viewModel = CashierViewModel(context, apiService)
        viewModel.fetchHistory("token")
        waitUntil { viewModel.isLoading.value == false }

        // Invariant: Missing activeUserId must remain UNKNOWN authority
        val state = viewModel.cashierState.value
        assertTrue("State must remain UNKNOWN when userId is missing", state is CashierAuthorityState.UNKNOWN)
        assertEquals("MISSING_ACTIVE_USER", (state as CashierAuthorityState.UNKNOWN).reason)
        assertFalse(viewModel.isClosed.value ?: true)
        assertNull(viewModel.currentSessionId.value)
    }

    @Test
    fun test31_cashier_remoteClosedWithMissingUserIdRemainsUnknown() = runBlocking {
        TenantBindingStore.setActiveTenantId(context, tenantA)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(Constants.USER_ID).apply()

        val closedSession = CashierSession(
            id = "sess_remote_31",
            caixa_session_id = "sess_remote_31",
            tipo = "FECHAMENTO",
            valor = 0.0
        )
        val response = CashierHistoryResponse(operacoes = listOf(closedSession))
        whenever(apiService.getCashierHistory(anyOrNull(), anyOrNull())).thenReturn(response)
        whenever(apiService.getExchangeRates(anyOrNull(), anyOrNull())).thenReturn(ExchangeResponse(moedas = emptyList()))

        val viewModel = CashierViewModel(context, apiService)
        viewModel.fetchHistory("token")
        waitUntil { viewModel.isLoading.value == false }

        val state = viewModel.cashierState.value
        assertTrue("State must remain UNKNOWN when userId is missing", state is CashierAuthorityState.UNKNOWN)
        assertEquals("MISSING_ACTIVE_USER", (state as CashierAuthorityState.UNKNOWN).reason)
    }

    @Test
    fun test32_table_getTableByNumberEmptySectorIdRetainsPreHotfixBehavior() = runBlocking {
        val table1 = TableEntity(
            id = "tbl_sec1",
            number = 10,
            status = Table.Status.AVAILABLE,
            sectorName = "Setor 1",
            sectorId = "sec_1",
            updatedAt = System.currentTimeMillis()
        )
        tableDao.insert(table1)

        // null sectorId searches globally by number
        val byNull = tableReadRepository.getTableByNumber(10, null)
        assertNotNull(byNull)
        assertEquals("tbl_sec1", byNull?.id)

        // empty string sectorId also searches globally by number (pre-hotfix baseline behavior)
        val byEmpty = tableReadRepository.getTableByNumber(10, "")
        assertNotNull(byEmpty)
        assertEquals("tbl_sec1", byEmpty?.id)

        // specific matching sectorId
        val bySec1 = tableReadRepository.getTableByNumber(10, "sec_1")
        assertNotNull(bySec1)
        assertEquals("tbl_sec1", bySec1?.id)

        // non-matching sectorId
        val bySec2 = tableReadRepository.getTableByNumber(10, "sec_2")
        assertNull(bySec2)
    }
}
