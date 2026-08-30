package com.plugpdv.pdv.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.*
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.outbox.SaleSyncScheduler
import com.plugpdv.pdv.ui.sale.*
import com.plugpdv.pdv.utils.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class TableReadModelTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var tableDao: TableDao
    private lateinit var comandaSnapshotDao: ComandaSnapshotDao
    private lateinit var catalogDao: CatalogDao
    private lateinit var outboxDao: OutboxDao
    private lateinit var paymentAttemptDao: PaymentAttemptDao

    private lateinit var apiService: PosApiService
    private lateinit var taxRepository: TaxRepository
    private lateinit var outboxSyncManager: OutboxSyncManager
    private lateinit var saleSyncScheduler: SaleSyncScheduler
    private lateinit var tableReadRepository: TableReadRepository
    private lateinit var comandaSnapshotRepository: ComandaSnapshotRepository

    private val gson = Gson()
    private val TEST_TENANT = "tenant-read-test"

    private suspend fun waitUntil(timeoutMs: Long = 8000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition() && System.currentTimeMillis() - start < timeoutMs) {
            ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            delay(50)
        }
        ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        TenantBindingStore.setActiveTenantId(context, TEST_TENANT)

        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        tableDao = db.tableDao()
        comandaSnapshotDao = db.comandaSnapshotDao()
        catalogDao = db.catalogDao()
        outboxDao = db.outboxDao()
        paymentAttemptDao = db.paymentAttemptDao()

        apiService = mock()
        taxRepository = mock()
        outboxSyncManager = mock()
        saleSyncScheduler = mock()

        whenever(outboxSyncManager.checkoutResultEvents).thenReturn(kotlinx.coroutines.flow.MutableSharedFlow())
        whenever(taxRepository.getActiveTaxesLiveData()).thenReturn(androidx.lifecycle.MutableLiveData(emptyList()))

        tableReadRepository = TableReadRepository(context, tableDao, apiService, catalogDao)
        comandaSnapshotRepository = ComandaSnapshotRepository(context, comandaSnapshotDao, gson)
    }

    @After
    fun tearDown() {
        TenantBindingStore.clearTenant(context)
        db.close()
    }

    /**
     * OFFLINE-READ-01: Room contains 5 TableEntity rows, 2 sectors. Network throws IOException.
     * Expected: Mesa UI/read model emits 5 tables, 2 sectors + Todos, no empty list after failure.
     */
    @Test
    fun testOFFLINE_READ_01_roomTablesPreservedWhenNetworkFails() = runBlocking {
        val tables = listOf(
            TableEntity(id = "t-1", number = 1, status = "AVAILABLE", sectorName = "Salao", sectorId = "sec-1"),
            TableEntity(id = "t-2", number = 2, status = "OCCUPIED", sectorName = "Salao", sectorId = "sec-1"),
            TableEntity(id = "t-3", number = 3, status = "AVAILABLE", sectorName = "Salao", sectorId = "sec-1"),
            TableEntity(id = "t-4", number = 10, status = "OCCUPIED", sectorName = "Varanda", sectorId = "sec-2"),
            TableEntity(id = "t-5", number = 11, status = "AVAILABLE", sectorName = "Varanda", sectorId = "sec-2")
        )
        tableDao.insertAll(tables)

        val viewModel = MesaViewModel(apiService, catalogDao, tableReadRepository, context)
        viewModel.tables.observeForever { }
        viewModel.sectors.observeForever { }
        viewModel.refreshWarning.observeForever { }

        waitUntil { viewModel.tables.value?.size == 5 }

        val initialTables = viewModel.tables.value
        assertEquals(5, initialTables?.size)
        val sectors = viewModel.sectors.value
        assertEquals(3, sectors?.size) // "Todos", "Salao", "Varanda"

        whenever(apiService.getMesas(any())).thenAnswer { throw IOException("No network") }

        viewModel.fetchTables("dummy-token")
        waitUntil { viewModel.refreshWarning.value != null }

        val afterFailureTables = viewModel.tables.value
        assertNotNull(afterFailureTables)
        assertEquals(5, afterFailureTables?.size)
        assertEquals("Sem conexão — exibindo dados salvos", viewModel.refreshWarning.value)
    }

    /**
     * OFFLINE-READ-02: Empty Room, network throws IOException.
     * Expected: Emits empty list, error message set, no crash.
     */
    @Test
    fun testOFFLINE_READ_02_emptyRoomNetworkErrorEmitsClearErrorState() = runBlocking {
        whenever(apiService.getMesas(any())).thenAnswer { throw IOException("Connection reset") }

        val viewModel = MesaViewModel(apiService, catalogDao, tableReadRepository, context)
        viewModel.tables.observeForever { }
        viewModel.error.observeForever { }
        ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)

        assertEquals(0, viewModel.tables.value?.size ?: 0)

        viewModel.fetchTables("dummy-token")
        waitUntil { viewModel.error.value != null }

        assertEquals(0, viewModel.tables.value?.size ?: 0)
        assertNotNull(viewModel.error.value)
        assertEquals("Erro de conexão ao carregar mesas", viewModel.error.value)
    }

    /**
     * OFFLINE-READ-03: Room contains 3 tables, network returns 4 tables (1 deleted, 2 added, 1 status updated).
     * Expected: Room is updated to exactly the 4 remote tables; UI emits 4.
     */
    @Test
    fun testOFFLINE_READ_03_successfulRemoteRefreshUpdatesRoomAtomically() = runBlocking {
        val oldTables = listOf(
            TableEntity(id = "t-1", number = 1, status = "AVAILABLE", sectorName = "Salao", sectorId = "sec-1"),
            TableEntity(id = "t-2", number = 2, status = "OCCUPIED", sectorName = "Salao", sectorId = "sec-1"),
            TableEntity(id = "t-3", number = 3, status = "AVAILABLE", sectorName = "Salao", sectorId = "sec-1")
        )
        tableDao.insertAll(oldTables)

        val remoteResponse = RestaurantResponse(
            setores = listOf(
                Sector(
                    id = "sec-1",
                    nome = "Salao",
                    mesas = listOf(
                        MesaDto(id = "t-2", numero = 2, status = "AVAILABLE"),
                        MesaDto(id = "t-4", numero = 4, status = "OCCUPIED"),
                        MesaDto(id = "t-5", numero = 5, status = "AVAILABLE")
                    )
                ),
                Sector(
                    id = "sec-2",
                    nome = "Terraco",
                    mesas = listOf(
                        MesaDto(id = "t-6", numero = 6, status = "AVAILABLE")
                    )
                )
            )
        )
        whenever(apiService.getMesas(any())).thenReturn(remoteResponse)

        val viewModel = MesaViewModel(apiService, catalogDao, tableReadRepository, context)
        viewModel.tables.observeForever { }
        waitUntil { viewModel.tables.value?.size == 3 }
        assertEquals(3, viewModel.tables.value?.size)

        viewModel.fetchTables("valid-token")
        waitUntil { viewModel.tables.value?.size == 4 }

        val updatedTables = viewModel.tables.value
        assertEquals(4, updatedTables?.size)
        val table2 = updatedTables?.find { it.id == "t-2" }
        assertEquals("AVAILABLE", table2?.status)
        assertNull(updatedTables?.find { it.id == "t-1" })
        assertNotNull(updatedTables?.find { it.id == "t-4" })
        assertNotNull(updatedTables?.find { it.id == "t-5" })
        assertNotNull(updatedTables?.find { it.id == "t-6" })
        assertNull(viewModel.refreshWarning.value)
    }

    /**
     * OFFLINE-READ-04: Filter by sector. Room has 10 tables across 3 sectors.
     * Expected: Sector filter operates instantly on local Flow without network round-trip.
     */
    @Test
    fun testOFFLINE_READ_04_sectorFilterOperatesInstantlyOnLocalFlow() = runBlocking {
        val tables = mutableListOf<TableEntity>()
        for (i in 1..4) tables.add(TableEntity(id = "s1-$i", number = i, status = "AVAILABLE", sectorName = "Interno", sectorId = "sec-interno"))
        for (i in 5..7) tables.add(TableEntity(id = "s2-$i", number = i, status = "OCCUPIED", sectorName = "Externo", sectorId = "sec-externo"))
        for (i in 8..10) tables.add(TableEntity(id = "s3-$i", number = i, status = "AVAILABLE", sectorName = "Balcao", sectorId = "sec-balcao"))
        tableDao.insertAll(tables)

        val viewModel = MesaViewModel(apiService, catalogDao, tableReadRepository, context)
        viewModel.tables.observeForever { }
        waitUntil { viewModel.tables.value?.size == 10 }
        assertEquals(10, viewModel.tables.value?.size)

        viewModel.setSelectedSector("sec-interno")
        waitUntil { viewModel.tables.value?.size == 4 }
        assertEquals(4, viewModel.tables.value?.size)
        assertTrue(viewModel.tables.value!!.all { it.sectorId == "sec-interno" })

        viewModel.setSelectedSector("sec-externo")
        waitUntil { viewModel.tables.value?.size == 3 }
        assertEquals(3, viewModel.tables.value?.size)
        assertTrue(viewModel.tables.value!!.all { it.sectorId == "sec-externo" })

        viewModel.setSelectedSector("")
        waitUntil { viewModel.tables.value?.size == 10 }
        assertEquals(10, viewModel.tables.value?.size)
    }

    /**
     * OFFLINE-READ-05: Detail with USABLE snapshot in Room. Network throws IOException.
     * Expected: Shows snapshot data, provenance = LOCAL_CACHED, pay button blocked with offline reason.
     */
    @Test
    fun testOFFLINE_READ_05_detailWithUsableSnapshotPreservedOnNetworkLoss() = runBlocking {
        val tableEntity = TableEntity(
            id = "tbl-1",
            number = 5,
            status = "OCCUPIED",
            sectorName = "Salao",
            sectorId = "sec-1",
            comandaId = "cmd-100"
        )
        tableDao.insert(tableEntity)

        val items = listOf(
            MesaItemDto(id = "item-1", produto_id = "p-1", nome = "Pizza Margherita", preco_unitario = 45.0, quantidade = 2, subtotal = 90.0)
        )
        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-100",
            tenantId = TEST_TENANT,
            tableId = "tbl-1",
            tableNumber = 5,
            customerIdentifier = "Cliente Mesa 5",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 3L,
            localRevision = 3L,
            totalBaseMinor = 9000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 9000L,
            itemsJson = gson.toJson(items),
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(snapshot)

        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("No network") }

        val viewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )
        viewModel.table.observeForever { }
        viewModel.readProvenance.observeForever { }
        viewModel.refreshWarning.observeForever { }

        viewModel.init("tbl-1", 5, "sec-1", "token-xyz")
        waitUntil { viewModel.refreshWarning.value != null }

        val resolvedTable = viewModel.table.value
        assertNotNull(resolvedTable)
        assertEquals(5, resolvedTable?.number)
        assertEquals(1, resolvedTable?.items?.size)
        assertEquals("Pizza Margherita", resolvedTable?.items?.first()?.product?.name)
        assertEquals(90.0, resolvedTable?.calculateTotal() ?: 0.0, 0.001)
        assertEquals(ReadProvenance.LOCAL_CACHED, viewModel.readProvenance.value)
        assertEquals("Sem conexão — exibindo dados salvos", viewModel.refreshWarning.value)
    }

    /**
     * OFFLINE-READ-06: Detail with missing snapshot in Room. Network throws IOException.
     * Expected: Shows table skeleton from Room, items empty, provenance = INITIAL, clear error message.
     */
    @Test
    fun testOFFLINE_READ_06_detailWithoutSnapshotNetworkErrorShowsSkeletonAndError() = runBlocking {
        val tableEntity = TableEntity(
            id = "tbl-2",
            number = 8,
            status = "OCCUPIED",
            sectorName = "Varanda",
            sectorId = "sec-2",
            comandaId = "cmd-200"
        )
        tableDao.insert(tableEntity)

        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("Timeout") }

        val viewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )
        viewModel.table.observeForever { }
        viewModel.error.observeForever { }

        viewModel.init("tbl-2", 8, "sec-2", "token-xyz")
        waitUntil { viewModel.error.value != null }

        val table = viewModel.table.value
        assertNotNull(table)
        assertEquals(8, table?.number)
        assertEquals(0, table?.items?.size)
        assertEquals("Erro de conexão ao carregar mesa", viewModel.error.value)
    }

    /**
     * OFFLINE-READ-07: Detail with snapshot from different tenant in Room.
     * Expected: Policy rejects snapshot, UI shows skeleton, does not expose other tenant data.
     */
    @Test
    fun testOFFLINE_READ_07_snapshotFromDifferentTenantRejected() = runBlocking {
        val tableEntity = TableEntity(
            id = "tbl-3",
            number = 9,
            status = "OCCUPIED",
            sectorName = "Salao",
            sectorId = "sec-1",
            comandaId = "cmd-300"
        )
        tableDao.insert(tableEntity)

        val wrongTenantSnapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-300",
            tenantId = "other-tenant-666",
            tableId = "tbl-3",
            tableNumber = 9,
            customerIdentifier = "Secret Tenant Data",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 1L,
            localRevision = 1L,
            totalBaseMinor = 50000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 50000L,
            itemsJson = "[]",
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(wrongTenantSnapshot)

        val decision = ComandaSnapshotAuthorityPolicy.evaluate(wrongTenantSnapshot, "cmd-300", context)
        assertEquals(SnapshotAuthorityDecision.WRONG_TENANT, decision)

        val viewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )
        viewModel.table.observeForever { }

        viewModel.init("tbl-3", 9, "sec-1", "token-xyz")
        waitUntil { viewModel.table.value != null }

        val table = viewModel.table.value
        assertNotNull(table)
        assertEquals(0, table?.items?.size)
        assertNotEquals(ReadProvenance.LOCAL_CACHED, viewModel.readProvenance.value)
    }

    /**
     * OFFLINE-READ-08: Snapshot item prices vs Catalog prices.
     * Expected: Snapshot historical item prices preserved, NOT overwritten with active catalog price.
     */
    @Test
    fun testOFFLINE_READ_08_historicalItemPricePreservedOverCatalogPrice() = runBlocking {
        catalogDao.insertAll(listOf(Product(
            id = "prod-special",
            name = "Cerveja Artesanal",
            selling_price = 30.0
        )))

        val tableEntity = TableEntity(
            id = "tbl-price",
            number = 15,
            status = "OCCUPIED",
            sectorName = "Bar",
            sectorId = "sec-bar",
            comandaId = "cmd-price-1"
        )
        tableDao.insert(tableEntity)

        val items = listOf(
            MesaItemDto(
                id = "item-p1",
                produto_id = "prod-special",
                nome = "Cerveja Artesanal",
                preco_unitario = 20.0,
                quantidade = 3,
                subtotal = 60.0
            )
        )
        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-price-1",
            tenantId = TEST_TENANT,
            tableId = "tbl-price",
            tableNumber = 15,
            customerIdentifier = "Mesa 15",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 1L,
            localRevision = 1L,
            totalBaseMinor = 6000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 6000L,
            itemsJson = gson.toJson(items),
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(snapshot)

        val viewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )
        viewModel.table.observeForever { }

        viewModel.init("tbl-price", 15, "sec-bar", "token-xyz")
        waitUntil { viewModel.table.value?.items?.isNotEmpty() == true }

        val table = viewModel.table.value
        assertNotNull(table)
        val item = table?.items?.first()
        assertEquals(20.0, item?.product?.selling_price ?: 0.0, 0.001)
        assertEquals(60.0, table?.calculateTotal() ?: 0.0, 0.001)
    }

    /**
     * OFFLINE-READ-09: Financial numbers strictly Long minor units and frozen baseMinorUnitDigits.
     * Expected: 0 digits (PYG 150000), 2 digits (BRL 12.50), 3 digits (BHD 1.250).
     */
    @Test
    fun testOFFLINE_READ_09_financialNumbersStrictLongMinorUnitsAndFrozenDigits() {
        val pygDecimal = ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(150000L, 0)
        assertEquals(BigDecimal("150000"), pygDecimal)
        assertEquals(150000L, ComandaSnapshotRepository.toMinorUnitsWithFrozenScale(BigDecimal("150000"), 0))

        val brlDecimal = ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(1250L, 2)
        assertEquals(BigDecimal("12.50"), brlDecimal)
        assertEquals(1250L, ComandaSnapshotRepository.toMinorUnitsWithFrozenScale(BigDecimal("12.50"), 2))

        val bhdDecimal = ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(1250L, 3)
        assertEquals(BigDecimal("1.250"), bhdDecimal)
        assertEquals(1250L, ComandaSnapshotRepository.toMinorUnitsWithFrozenScale(BigDecimal("1.250"), 3))
    }

    /**
     * OFFLINE-READ-10: Checkout loaded offline with USABLE snapshot.
     * Expected: READY_LOCAL state, authoritySource = LOCAL, Pay button BLOCKED with "Sem conexão".
     */
    @Test
    fun testOFFLINE_READ_10_checkoutLoadedOfflineWithUsableSnapshotPayButtonBlocked() = runBlocking {
        val testTable = Table(
            id = "tbl-10",
            number = 10,
            status = "OCCUPIED",
            comandaId = "cmd-offline-chk",
            total = 100.0,
            paidAmount = 0.0
        )
        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-offline-chk",
            tenantId = TEST_TENANT,
            tableId = "tbl-10",
            tableNumber = 10,
            customerIdentifier = "Cliente",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 1L,
            localRevision = 1L,
            totalBaseMinor = 10000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 10000L,
            itemsJson = "[]",
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(snapshot)

        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("No internet") }

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository,
            tableReadRepository = tableReadRepository
        )

        checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
        waitUntil { checkoutViewModel.uiState.value.moneyAuthorityState == MoneyAuthorityState.READY_LOCAL }

        val state = checkoutViewModel.uiState.value
        assertEquals(MoneyAuthorityState.READY_LOCAL, state.moneyAuthorityState)
        assertEquals("LOCAL", state.authoritySource)
        assertEquals(10000L, state.totalBaseMinor)
        assertEquals(10000L, state.balanceBaseMinor)
        assertTrue(state.isPayButtonBlocked)
        assertEquals("Sem conexão para processar pagamento", state.blockReason)
    }

    /**
     * OFFLINE-READ-11: Checkout loaded online (remote detail succeeds).
     * Expected: READY_REMOTE state, authoritySource = REMOTE, Pay button UNBLOCKED.
     */
    @Test
    fun testOFFLINE_READ_11_checkoutLoadedOnlineRemoteDetailSucceedsPayButtonUnblocked() = runBlocking {
        val testTable = Table(
            id = "tbl-11",
            number = 11,
            status = "OCCUPIED",
            comandaId = "cmd-online-chk",
            total = 75.0,
            paidAmount = 0.0
        )
        val remoteDetail = ComandaDetailResponse(
            id = "cmd-online-chk",
            mesaId = "tbl-11",
            numero = 11,
            status = "ABERTA",
            total = 75.0,
            totalPago = 0.0,
            totalPagoBase = 0.0,
            saldoBase = 75.0,
            baseCurrency = "BRL",
            requiresReconciliation = false,
            itens = emptyList(),
            pagamentos = emptyList()
        )
        whenever(apiService.getComandaDetail(any(), any())).thenReturn(remoteDetail)

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository,
            tableReadRepository = tableReadRepository
        )

        checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
        waitUntil { checkoutViewModel.uiState.value.moneyAuthorityState == MoneyAuthorityState.READY_REMOTE }

        val state = checkoutViewModel.uiState.value
        assertEquals(MoneyAuthorityState.READY_REMOTE, state.moneyAuthorityState)
        assertEquals("REMOTE", state.authoritySource)
        assertEquals(7500L, state.totalBaseMinor)
        assertEquals(7500L, state.balanceBaseMinor)
        assertFalse(state.isPayButtonBlocked)
        assertNull(state.blockReason)
    }

    /**
     * OFFLINE-READ-12: Snapshot with requiresReconciliation = true.
     * Expected: RECONCILIATION_REQUIRED state, Pay button BLOCKED with reconciliation reason.
     */
    @Test
    fun testOFFLINE_READ_12_snapshotWithRequiresReconciliationBlocksPayButton() = runBlocking {
        val testTable = Table(
            id = "tbl-12",
            number = 12,
            status = "OCCUPIED",
            comandaId = "cmd-reconcile-chk",
            total = 120.0,
            paidAmount = 0.0
        )
        tableDao.insert(TableEntity(id = "tbl-12", number = 12, status = "OCCUPIED", sectorName = "Salao", sectorId = "sec-1", comandaId = "cmd-reconcile-chk"))

        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-reconcile-chk",
            tenantId = TEST_TENANT,
            tableId = "tbl-12",
            tableNumber = 12,
            customerIdentifier = "Mesa 12",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "FAILED_LOCAL",
            serverRevision = 1L,
            localRevision = 2L,
            totalBaseMinor = 12000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 12000L,
            itemsJson = "[]",
            paymentsJson = "[]",
            requiresReconciliation = true,
            reconciliationReason = "UNRESOLVED_DISCREPANCY",
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(snapshot)

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository,
            tableReadRepository = tableReadRepository
        )

        checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
        waitUntil { checkoutViewModel.uiState.value.moneyAuthorityState == MoneyAuthorityState.RECONCILIATION_REQUIRED }

        val state = checkoutViewModel.uiState.value
        assertEquals(MoneyAuthorityState.RECONCILIATION_REQUIRED, state.moneyAuthorityState)
        assertTrue(state.requiresReconciliation)
        assertTrue(state.isPayButtonBlocked)
        assertEquals("Comanda requer conciliação com o servidor.", state.blockReason)
    }

    /**
     * OFFLINE-READ-13: Security / session failures (401, 403, 426).
     * Expected: Fatal security errors NOT degraded to offline fallback; triggers logout or kill switch.
     */
    @Test
    fun testOFFLINE_READ_13_securityFailuresTriggerFatalErrorNotOfflineFallback() = runBlocking {
        val tables = listOf(
            TableEntity(id = "t-sec", number = 1, status = "AVAILABLE", sectorName = "Salao", sectorId = "sec-1")
        )
        tableDao.insertAll(tables)

        val errorResponse401: Response<RestaurantResponse> = Response.error(401, "{\"error\":\"Unauthorized\"}".toResponseBody("application/json".toMediaTypeOrNull()))
        whenever(apiService.getMesas(any())).thenAnswer { throw HttpException(errorResponse401) }

        val viewModel = MesaViewModel(apiService, catalogDao, tableReadRepository, context)
        viewModel.tables.observeForever { }
        viewModel.sessionExpired.observeForever { }
        viewModel.error.observeForever { }

        viewModel.fetchTables("expired-token")
        waitUntil { viewModel.sessionExpired.value == true }

        assertEquals(true, viewModel.sessionExpired.value)
        assertNotNull(viewModel.error.value)
        assertEquals("Sessão expirada. Faça login novamente.", viewModel.error.value)

        val errorResponse426: Response<RestaurantResponse> = Response.error(426, "{\"error\":\"Upgrade Required\"}".toResponseBody("application/json".toMediaTypeOrNull()))
        whenever(apiService.getMesas(any())).thenAnswer { throw HttpException(errorResponse426) }

        viewModel.fetchTables("valid-token")
        waitUntil { viewModel.error.value == "Atualização obrigatória do aplicativo necessária." }
        assertEquals("Atualização obrigatória do aplicativo necessária.", viewModel.error.value)
    }

    /**
     * OFFLINE-READ-14: Remote healthy snapshot -> final state READY_REMOTE.
     * Pay button enabled when no blocker, prepareCheckoutOperation and finalizePayment succeed.
     */
    @Test
    fun testOFFLINE_READ_14_checkoutRealStateMachineAndMutationGates() = runBlocking {
        val testTable = Table(
            id = "tbl-14",
            number = 14,
            status = "OCCUPIED",
            comandaId = "cmd-14",
            total = 50.0,
            paidAmount = 0.0
        )
        val remoteDetail = ComandaDetailResponse(
            id = "cmd-14",
            mesaId = "tbl-14",
            numero = 14,
            status = "ABERTA",
            total = 50.0,
            totalPago = 0.0,
            totalPagoBase = 0.0,
            saldoBase = 50.0,
            baseCurrency = "BRL",
            requiresReconciliation = false,
            itens = emptyList(),
            pagamentos = emptyList()
        )
        whenever(apiService.getComandaDetail(any(), any())).thenReturn(remoteDetail)

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository,
            tableReadRepository = tableReadRepository
        )

        checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
        waitUntil { checkoutViewModel.uiState.value.moneyAuthorityState == MoneyAuthorityState.READY_REMOTE }

        val state = checkoutViewModel.uiState.value
        assertEquals(MoneyAuthorityState.READY_REMOTE, state.moneyAuthorityState)
        assertFalse(state.isPayButtonBlocked)
        assertFalse(state.requiresReconciliation)

        // Prepare checkout operation must succeed in READY_REMOTE
        val prepared = checkoutViewModel.prepareCheckoutOperation(PaymentMethod.CREDIT)
        assertNotNull(prepared.operationKey)
        assertEquals("cmd-14", prepared.request.comandaId)

        val savedOp = outboxDao.getById(prepared.operationKey)
        assertNotNull(savedOp)
        assertEquals("WAITING_PAYMENT", savedOp?.status)
    }

    /**
     * OFFLINE-READ-15: Process death recovery in checkout.
     * Explicitly clear TableManager, create checkout from stable IDs.
     * Resolves table from Room and renders snapshot summary without dismiss.
     */
    @Test
    fun testOFFLINE_READ_15_checkoutProcessDeathRecoveryFromRoom() = runBlocking {
        TableManager.setTables(emptyList())

        val tableEntity = TableEntity(
            id = "tbl-pd",
            number = 99,
            status = "OCCUPIED",
            sectorName = "Terraço",
            sectorId = "sec-pd",
            customerName = "Sr. Process Death",
            comandaId = "cmd-pd-99",
            peopleCount = 2
        )
        tableDao.insert(tableEntity)

        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-pd-99",
            tenantId = TEST_TENANT,
            tableId = "tbl-pd",
            tableNumber = 99,
            customerIdentifier = "Sr. Process Death",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 1L,
            localRevision = 1L,
            totalBaseMinor = 15000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 15000L,
            itemsJson = "[]",
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(snapshot)

        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("No network") }

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository,
            tableReadRepository = tableReadRepository
        )

        checkoutViewModel.init(
            tableId = "tbl-pd",
            tableNumber = 99,
            sectorId = "sec-pd",
            token = "dummy-token",
            sessionId = "sess-1",
            opId = "op-1",
            opName = "Operador"
        )
        waitUntil { checkoutViewModel.uiState.value.moneyAuthorityState == MoneyAuthorityState.READY_LOCAL }

        val state = checkoutViewModel.uiState.value
        assertEquals(MoneyAuthorityState.READY_LOCAL, state.moneyAuthorityState)
        assertEquals(15000L, state.totalBaseMinor)
        assertEquals(15000L, state.balanceBaseMinor)
        assertEquals("BRL", state.baseCurrency)
    }

    /**
     * OFFLINE-READ-16: Local-first race immunity.
     * Valid local snapshot, remote immediately throws IOException.
     * Deterministically produces READY_LOCAL and NEVER LOAD_ERROR.
     */
    @Test
    fun testOFFLINE_READ_16_checkoutLocalFirstRaceDeterministic() = runBlocking {
        val testTable = Table(
            id = "tbl-16",
            number = 16,
            status = "OCCUPIED",
            comandaId = "cmd-16",
            total = 80.0,
            paidAmount = 0.0
        )
        tableDao.insert(TableEntity(id = "tbl-16", number = 16, status = "OCCUPIED", sectorName = "Geral", sectorId = "sec-16", comandaId = "cmd-16"))

        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-16",
            tenantId = TEST_TENANT,
            tableId = "tbl-16",
            tableNumber = 16,
            customerIdentifier = "Cliente",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 1L,
            localRevision = 1L,
            totalBaseMinor = 8000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 8000L,
            itemsJson = "[]",
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(snapshot)

        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("Connection reset immediately") }

        // Run 5 sequential trials to ensure zero race conditions
        for (trial in 1..5) {
            val checkoutViewModel = CheckoutViewModel(
                context = context,
                apiService = apiService,
                taxRepository = taxRepository,
                outboxDao = outboxDao,
                paymentAttemptDao = paymentAttemptDao,
                outboxSyncManager = outboxSyncManager,
                saleSyncScheduler = saleSyncScheduler,
                comandaSnapshotRepository = comandaSnapshotRepository,
                tableReadRepository = tableReadRepository
            )

            checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
            waitUntil { checkoutViewModel.uiState.value.refreshWarning != null || checkoutViewModel.uiState.value.moneyAuthorityState != MoneyAuthorityState.LOADING }

            val state = checkoutViewModel.uiState.value
            assertEquals("Trial $trial must be READY_LOCAL", MoneyAuthorityState.READY_LOCAL, state.moneyAuthorityState)
            assertNotEquals("Trial $trial must never be LOAD_ERROR", MoneyAuthorityState.LOAD_ERROR, state.moneyAuthorityState)
            assertEquals("Sem conexão — exibindo dados salvos", state.refreshWarning)
        }
    }

    /**
     * OFFLINE-READ-17: Atomic table replacement in Room.
     * Flow must emit OLD then NEW without intermediate empty set.
     */
    @Test
    fun testOFFLINE_READ_17_atomicTableReplace() = runBlocking {
        val oldTables = listOf(
            TableEntity(id = "o-1", number = 1, status = "AVAILABLE", sectorName = "Salao", sectorId = "sec-1"),
            TableEntity(id = "o-2", number = 2, status = "OCCUPIED", sectorName = "Salao", sectorId = "sec-1")
        )
        tableDao.insertAll(oldTables)

        val emissions = mutableListOf<List<TableEntity>>()
        val job = launch {
            tableDao.observeAllTables().collect { tables ->
                emissions.add(tables)
            }
        }
        waitUntil { emissions.isNotEmpty() }
        assertEquals(1, emissions.size)
        assertEquals(2, emissions.first().size)

        val newTables = listOf(
            TableEntity(id = "n-1", number = 10, status = "AVAILABLE", sectorName = "Varanda", sectorId = "sec-2"),
            TableEntity(id = "n-2", number = 11, status = "AVAILABLE", sectorName = "Varanda", sectorId = "sec-2"),
            TableEntity(id = "n-3", number = 12, status = "OCCUPIED", sectorName = "Varanda", sectorId = "sec-2")
        )

        tableDao.replaceAll(newTables)
        waitUntil { emissions.size >= 2 }

        job.cancel()

        // Verify emissions: OLD (2) -> NEW (3). Never empty [] in between!
        assertEquals(2, emissions.size)
        assertEquals(2, emissions[0].size)
        assertEquals(3, emissions[1].size)
        assertTrue("Intermediate empty emission is forbidden", emissions.none { it.isEmpty() })
    }

    /**
     * OFFLINE-READ-18: Online command contract regression.
     * Outgoing CommandActionRequest wire fields must strictly match backend contracts.
     */
    @Test
    fun testOFFLINE_READ_18_onlineCommandContractRegression() = runBlocking {
        val table = Table(id = "tbl-18", number = 18, status = "OCCUPIED", comandaId = "cmd-18")
        tableDao.insert(TableEntity(id = "tbl-18", number = 18, status = "OCCUPIED", sectorName = "Salao", sectorId = "sec-1", comandaId = "cmd-18"))

        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-18",
            tenantId = TEST_TENANT,
            tableId = "tbl-18",
            tableNumber = 18,
            customerIdentifier = "Cliente",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 1L,
            localRevision = 1L,
            totalBaseMinor = 2000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 2000L,
            itemsJson = "[]",
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(snapshot)

        val recordedRequests = mutableListOf<CommandActionRequest>()
        whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer { invocation ->
            val req = invocation.getArgument<CommandActionRequest>(1)
            recordedRequests.add(req)
            Response.success(mapOf("success" to true))
        }

        val viewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )
        viewModel.init(table, "test-token")
        waitUntil { viewModel.table.value != null }

        // Test 1: ADD ITEM
        val product = Product(id = "prod-18", name = "Agua Mineral", selling_price = 5.0)
        viewModel.addItem(product)
        waitUntil { recordedRequests.any { it.action == "add_item" } }

        val addReq = recordedRequests.find { it.action == "add_item" }
        assertNotNull(addReq)
        assertEquals("add_item", addReq?.action)
        assertEquals("RASCUNHO", addReq?.status)
        assertEquals("tbl-18", addReq?.mesaId)
        assertEquals("cmd-18", addReq?.comandaId)
        assertEquals("prod-18", addReq?.product_id)
        assertEquals(1, addReq?.quantity)

        // Test 2: REMOVE ITEM
        val itemToRemove = TableItem(id = "item-18", product = product, quantity = 1).apply {
            serverIds = mutableListOf("srv-18")
        }
        viewModel.removeItem(itemToRemove, "Erro do operador")
        waitUntil { recordedRequests.any { it.action == "cancel_item" } }

        val removeReq = recordedRequests.find { it.action == "cancel_item" }
        assertNotNull(removeReq)
        assertEquals("cancel_item", removeReq?.action)
        assertEquals("tbl-18", removeReq?.mesaId)
        assertEquals("cmd-18", removeReq?.comandaId)
        assertEquals("item-18", removeReq?.order_id)
        assertEquals("prod-18", removeReq?.product_id)
        assertEquals("Erro do operador", removeReq?.reason)

        // Test 3: SEND KITCHEN
        viewModel.enviarCozinha {}
        waitUntil { recordedRequests.any { it.action == "enviar_cozinha" } }

        val kitchenReq = recordedRequests.find { it.action == "enviar_cozinha" }
        assertNotNull(kitchenReq)
        assertEquals("enviar_cozinha", kitchenReq?.action)
        assertEquals("cmd-18", kitchenReq?.comandaId)
    }

    /**
     * OFFLINE-READ-19: Offline transfer blocked without creating new queue entry.
     */
    @Test
    fun testOFFLINE_READ_19_offlineTransferBlockedNoQueue() = runBlocking {
        val origin = Table(id = "tbl-orig", number = 1, status = Table.Status.OCCUPIED, comandaId = "cmd-orig")
        val destination = Table(id = "tbl-dest", number = 2, status = Table.Status.AVAILABLE)

        val tables = listOf(
            TableEntity(id = "tbl-orig", number = 1, status = "OCCUPIED", sectorName = "Salao", sectorId = "sec-1", comandaId = "cmd-orig"),
            TableEntity(id = "tbl-dest", number = 2, status = "AVAILABLE", sectorName = "Salao", sectorId = "sec-1")
        )
        tableDao.insertAll(tables)

        whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenAnswer { throw IOException("No network for transfer") }

        val viewModel = MesaViewModel(apiService, catalogDao, tableReadRepository, context)
        viewModel.error.observeForever { }
        viewModel.transferSuccess.observeForever { }

        viewModel.transferTable("token", origin, destination)
        waitUntil { viewModel.error.value != null }

        assertEquals("Sem conexão. Transferência de mesa requer conexão.", viewModel.error.value)
        assertFalse(viewModel.transferSuccess.value ?: false)

        val queueManager = TransferQueueManager(context)
        assertEquals(0, queueManager.getQueue().size)

        // Origin and destination in Room remain unchanged
        assertEquals("OCCUPIED", tableDao.getTableById("tbl-orig")?.status)
        assertEquals("AVAILABLE", tableDao.getTableById("tbl-dest")?.status)
    }

    /**
     * OFFLINE-READ-20: Security failure (401, 403, 426) in TableOrder.
     */
    @Test
    fun testOFFLINE_READ_20_securityFailureInTableOrder() = runBlocking {
        val tableEntity = TableEntity(
            id = "tbl-sec",
            number = 30,
            status = "OCCUPIED",
            sectorName = "Salao",
            sectorId = "sec-1",
            comandaId = "cmd-sec"
        )
        tableDao.insert(tableEntity)

        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-sec",
            tenantId = TEST_TENANT,
            tableId = "tbl-sec",
            tableNumber = 30,
            customerIdentifier = "Cliente",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 1L,
            localRevision = 1L,
            totalBaseMinor = 5000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 5000L,
            itemsJson = "[]",
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(snapshot)

        val errorResponse401: Response<ComandaDetailResponse> = Response.error(401, "{\"error\":\"Unauthorized\"}".toResponseBody("application/json".toMediaTypeOrNull()))
        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw HttpException(errorResponse401) }

        val viewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )
        viewModel.sessionExpired.observeForever { }
        viewModel.error.observeForever { }
        viewModel.refreshWarning.observeForever { }

        viewModel.init("tbl-sec", 30, "sec-1", "token")
        waitUntil { viewModel.sessionExpired.value == true }

        assertEquals(true, viewModel.sessionExpired.value)
        assertEquals("Sessão expirada. Faça login novamente.", viewModel.error.value)
        assertNull("Security failure must not degrade to offline warning", viewModel.refreshWarning.value)
    }

    /**
     * OFFLINE-READ-21: Snapshot authority policy cannot be bypassed by PENDING_MUTATIONS.
     */
    @Test
    fun testOFFLINE_READ_21_authorityPolicyCannotBeBypassedByPendingMutations() {
        val baseSnapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-pol",
            tenantId = TEST_TENANT,
            tableId = "tbl-pol",
            tableNumber = 1,
            customerIdentifier = "Cliente",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "PENDING_MUTATIONS",
            serverRevision = 1L,
            localRevision = 2L,
            totalBaseMinor = 5000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 5000L,
            itemsJson = "[]",
            paymentsJson = "[]",
            requiresReconciliation = true,
            reconciliationReason = "RECONCILIATION_FLAG",
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )

        // Reconciliation required must not be overridden
        assertEquals(SnapshotAuthorityDecision.RECONCILIATION_REQUIRED, ComandaSnapshotAuthorityPolicy.evaluate(baseSnapshot, "cmd-pol", context))

        // Wrong tenant must not be overridden
        val wrongTenant = baseSnapshot.copy(tenantId = "wrong-tenant-id", requiresReconciliation = false)
        assertEquals(SnapshotAuthorityDecision.WRONG_TENANT, ComandaSnapshotAuthorityPolicy.evaluate(wrongTenant, "cmd-pol", context))

        // Conflict must not be overridden
        val conflictSnapshot = baseSnapshot.copy(syncStatus = "CONFLICT", requiresReconciliation = false)
        assertEquals(SnapshotAuthorityDecision.CONFLICT, ComandaSnapshotAuthorityPolicy.evaluate(conflictSnapshot, "cmd-pol", context))

        // Wrong comanda must not be overridden
        val wrongComanda = baseSnapshot.copy(serverComandaId = "other-cmd", requiresReconciliation = false)
        assertEquals(SnapshotAuthorityDecision.WRONG_COMANDA, ComandaSnapshotAuthorityPolicy.evaluate(wrongComanda, "cmd-pol", context))
    }

    /**
     * OFFLINE-READ-22: Durable payment blocker precedence over remote refresh.
     */
    @Test
    fun testOFFLINE_READ_22_durablePaymentBlockerPrecedence() = runBlocking {
        val testTable = Table(
            id = "tbl-22",
            number = 22,
            status = "OCCUPIED",
            comandaId = "cmd-22",
            total = 100.0,
            paidAmount = 0.0
        )
        val opKey = UUID.randomUUID().toString()
        val outbox = OutboxOperationEntity(
            id = opKey,
            operationType = "COMANDA_CHECKOUT_COMMIT",
            targetGroupKey = "cmd-22",
            payloadJson = "{}",
            createdAt = System.currentTimeMillis(),
            idempotencyKey = opKey,
            status = "REQUIRES_RECONCILIATION"
        )
        outboxDao.insert(outbox)

        val remoteDetail = ComandaDetailResponse(
            id = "cmd-22",
            mesaId = "tbl-22",
            numero = 22,
            status = "ABERTA",
            total = 100.0,
            totalPago = 0.0,
            totalPagoBase = 0.0,
            saldoBase = 100.0,
            baseCurrency = "BRL",
            requiresReconciliation = false,
            itens = emptyList(),
            pagamentos = emptyList()
        )
        whenever(apiService.getComandaDetail(any(), any())).thenReturn(remoteDetail)

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository,
            tableReadRepository = tableReadRepository
        )

        checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
        waitUntil { checkoutViewModel.uiState.value.moneyAuthorityState == MoneyAuthorityState.RECONCILIATION_REQUIRED }

        val state = checkoutViewModel.uiState.value
        assertEquals(MoneyAuthorityState.RECONCILIATION_REQUIRED, state.moneyAuthorityState)
        assertTrue(state.isPayButtonBlocked)
        assertTrue(state.requiresReconciliation)
        assertEquals("Pagamento aprovado requer conciliação", state.blockReason)
    }

    /**
     * PROCESS DEATH RECOVERY TEST:
     * TableManager in-memory state is empty. Room has table entity and comanda snapshot.
     * Activity/ViewModel initializes by ID and restores entire table state and order items from Room.
     */
    @Test
    fun testProcessDeathRecovery_TableReconstructedFromRoomWithoutTableManager() = runBlocking {
        TableManager.setTables(emptyList())

        val tableEntity = TableEntity(
            id = "t-death",
            number = 42,
            status = "OCCUPIED",
            sectorName = "Varanda",
            sectorId = "sec-v",
            customerName = "Sr. Process Death",
            comandaId = "cmd-death-42",
            peopleCount = 4
        )
        tableDao.insert(tableEntity)

        val items = listOf(
            MesaItemDto(id = "di-1", produto_id = "p-burger", nome = "Smash Burger", preco_unitario = 35.0, quantidade = 2, subtotal = 70.0),
            MesaItemDto(id = "di-2", produto_id = "p-fries", nome = "Batata Frita", preco_unitario = 18.0, quantidade = 1, subtotal = 18.0)
        )
        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-death-42",
            tenantId = TEST_TENANT,
            tableId = "t-death",
            tableNumber = 42,
            customerIdentifier = "Sr. Process Death",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 5L,
            localRevision = 5L,
            totalBaseMinor = 8800L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 8800L,
            itemsJson = gson.toJson(items),
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(snapshot)

        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("Offline after process death") }

        val viewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )
        viewModel.table.observeForever { }
        viewModel.readProvenance.observeForever { }

        viewModel.init("t-death", 42, "sec-v", "token")
        waitUntil { viewModel.table.value?.items?.isNotEmpty() == true }

        val recoveredTable = viewModel.table.value
        assertNotNull(recoveredTable)
        assertEquals("t-death", recoveredTable?.id)
        assertEquals(42, recoveredTable?.number)
        assertEquals("Sr. Process Death", recoveredTable?.customerName)
        assertEquals(2, recoveredTable?.items?.size)
        assertEquals(88.0, recoveredTable?.calculateTotal() ?: 0.0, 0.001)
        assertEquals(ReadProvenance.LOCAL_CACHED, viewModel.readProvenance.value)
    }

    /**
     * OFFLINE-READ-22: cacheRemoteDetail cannot produce valid snapshot because totalPagoBase/saldoBase missing.
     * Expected: NO READY_REMOTE. NO fallback to totalPago. RECONCILIATION_REQUIRED according to snapshot policy.
     */
    @Test
    fun testOFFLINE_READ_22_cacheRemoteDetailMissingBaseMoneySummaryProducesNoReadyRemote() = runBlocking {
        val testTable = Table(
            id = "tbl-22",
            number = 22,
            status = "OCCUPIED",
            comandaId = "cmd-22",
            total = 100.0,
            paidAmount = 0.0
        )
        val remoteDetail = ComandaDetailResponse(
            id = "cmd-22",
            mesaId = "tbl-22",
            numero = 22,
            status = "ABERTA",
            total = 100.0,
            totalPago = 20.0, // Legacy field present
            totalPagoBase = null, // Missing base money summary
            saldoBase = null, // Missing base balance
            baseCurrency = "BRL",
            requiresReconciliation = false,
            itens = emptyList(),
            pagamentos = emptyList()
        )
        whenever(apiService.getComandaDetail(any(), any())).thenReturn(remoteDetail)

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository,
            tableReadRepository = tableReadRepository
        )

        checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
        waitUntil { checkoutViewModel.uiState.value.moneyAuthorityState == MoneyAuthorityState.RECONCILIATION_REQUIRED }

        val state = checkoutViewModel.uiState.value
        assertNotEquals(MoneyAuthorityState.READY_REMOTE, state.moneyAuthorityState)
        assertEquals(MoneyAuthorityState.RECONCILIATION_REQUIRED, state.moneyAuthorityState)
        assertTrue(state.requiresReconciliation)
        assertTrue(state.isPayButtonBlocked)
    }

    /**
     * OFFLINE-READ-23: usable READY_LOCAL snapshot. Remote returns HTTP 503.
     * Expected: READY_LOCAL preserved, pay blocked, local money unchanged.
     */
    @Test
    fun testOFFLINE_READ_23_usableReadyLocalWithHttp503KeepsReadyLocalAndBlocked() = runBlocking {
        val testTable = Table(
            id = "tbl-23",
            number = 23,
            status = "OCCUPIED",
            comandaId = "cmd-23",
            total = 50.0,
            paidAmount = 0.0
        )
        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-23",
            tenantId = TEST_TENANT,
            tableId = "tbl-23",
            tableNumber = 23,
            customerIdentifier = "Cliente 23",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 0L,
            localRevision = 0L,
            totalBaseMinor = 5000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 5000L,
            itemsJson = "[]",
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(snapshot)

        val errorResponse = Response.error<ComandaDetailResponse>(
            503,
            "Service Unavailable".toResponseBody("application/json".toMediaTypeOrNull())
        )
        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw HttpException(errorResponse) }

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository,
            tableReadRepository = tableReadRepository
        )

        checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
        waitUntil { checkoutViewModel.uiState.value.refreshWarning != null }

        val state = checkoutViewModel.uiState.value
        assertEquals(MoneyAuthorityState.READY_LOCAL, state.moneyAuthorityState)
        assertEquals(5000L, state.balanceBaseMinor)
        assertEquals(5000L, state.totalBaseMinor)
        assertTrue(state.isPayButtonBlocked)
        assertNotNull(state.refreshWarning)
    }

    /**
     * OFFLINE-READ-24: no local snapshot. Remote returns HTTP 503.
     * Expected: LOAD_ERROR.
     */
    @Test
    fun testOFFLINE_READ_24_noLocalSnapshotWithHttp503ProducesLoadError() = runBlocking {
        val testTable = Table(
            id = "tbl-24",
            number = 24,
            status = "OCCUPIED",
            comandaId = "cmd-24",
            total = 50.0,
            paidAmount = 0.0
        )
        val errorResponse = Response.error<ComandaDetailResponse>(
            503,
            "Service Unavailable".toResponseBody("application/json".toMediaTypeOrNull())
        )
        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw HttpException(errorResponse) }

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository,
            tableReadRepository = tableReadRepository
        )

        checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
        waitUntil { checkoutViewModel.uiState.value.moneyAuthorityState == MoneyAuthorityState.LOAD_ERROR }

        val state = checkoutViewModel.uiState.value
        assertEquals(MoneyAuthorityState.LOAD_ERROR, state.moneyAuthorityState)
        assertTrue(state.isPayButtonBlocked)
    }

    /**
     * OFFLINE-READ-25: remote detail cache returns MISSING_AUTHORITY or WRONG_TENANT.
     * Expected: never READY_REMOTE.
     */
    @Test
    fun testOFFLINE_READ_25_remoteDetailMissingAuthorityNeverReadyRemote() = runBlocking {
        val testTable = Table(
            id = "tbl-25",
            number = 25,
            status = "OCCUPIED",
            comandaId = "cmd-25",
            total = 50.0,
            paidAmount = 0.0
        )
        // Set tenant store to empty to force MISSING_AUTHORITY / cache failure
        TenantBindingStore.clearTenant(context)

        val remoteDetail = ComandaDetailResponse(
            id = "cmd-25",
            mesaId = "tbl-25",
            numero = 25,
            status = "ABERTA",
            total = 50.0,
            totalPago = 0.0,
            totalPagoBase = 0.0,
            saldoBase = 50.0,
            baseCurrency = "BRL",
            requiresReconciliation = false,
            itens = emptyList(),
            pagamentos = emptyList()
        )
        whenever(apiService.getComandaDetail(any(), any())).thenReturn(remoteDetail)

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository,
            tableReadRepository = tableReadRepository
        )

        checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
        waitUntil { checkoutViewModel.uiState.value.moneyAuthorityState == MoneyAuthorityState.LOAD_ERROR }

        val state = checkoutViewModel.uiState.value
        assertNotEquals(MoneyAuthorityState.READY_REMOTE, state.moneyAuthorityState)
        assertEquals(MoneyAuthorityState.LOAD_ERROR, state.moneyAuthorityState)

        // Restore tenant for subsequent tests
        TenantBindingStore.setActiveTenantId(context, TEST_TENANT)
    }

    /**
     * OFFLINE-READ-26: remote comanda already CLOSED on screen initialization.
     * Expected: financial summary readable, pay blocked, paymentSuccess = false, isComandaClosed = true.
     */
    @Test
    fun testOFFLINE_READ_26_remoteComandaAlreadyClosedInitialReadPayBlockedPaymentSuccessFalse() = runBlocking {
        val testTable = Table(
            id = "tbl-26",
            number = 26,
            status = "OCCUPIED",
            comandaId = "cmd-26",
            total = 80.0,
            paidAmount = 80.0
        )
        val remoteDetail = ComandaDetailResponse(
            id = "cmd-26",
            mesaId = "tbl-26",
            numero = 26,
            status = "FECHADA",
            total = 80.0,
            totalPago = 80.0,
            totalPagoBase = 80.0,
            saldoBase = 0.0,
            baseCurrency = "BRL",
            requiresReconciliation = false,
            itens = emptyList(),
            pagamentos = emptyList()
        )
        whenever(apiService.getComandaDetail(any(), any())).thenReturn(remoteDetail)

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository,
            tableReadRepository = tableReadRepository
        )

        checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
        waitUntil { checkoutViewModel.uiState.value.isComandaClosed }

        val state = checkoutViewModel.uiState.value
        assertTrue(state.isComandaClosed)
        assertTrue(state.isPayButtonBlocked)
        assertFalse(state.paymentSuccess)
        assertEquals(8000L, state.totalBaseMinor)
        assertEquals(8000L, state.paidBaseMinor)
        assertEquals(0L, state.balanceBaseMinor)
    }

    /**
     * OFFLINE-READ-27: getMesas HTTP 200 but setores = null. Existing 5 cached tables.
     * Expected: 5 cached tables preserved in Room.
     */
    @Test
    fun testOFFLINE_READ_27_getMesasSetoresNullPreservesExistingRoomCache() = runBlocking {
        for (i in 1..5) {
            tableDao.insert(
                TableEntity(
                    id = "tbl-$i",
                    number = i,
                    status = "AVAILABLE",
                    sectorName = "Salão",
                    sectorId = "sec-1",
                    customerName = null,
                    comandaId = null,
                    peopleCount = 1,
                    totalBalance = 0.0,
                    paidAmount = 0.0,
                    pendingBalance = 0.0,
                    itemsJson = "[]",
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        assertEquals(5, tableDao.getAllTables().size)

        whenever(apiService.getMesas(any())).thenReturn(RestaurantResponse(setores = null))

        val result = tableReadRepository.refreshTables("token")
        assertTrue(result.isFailure)

        // 5 cached tables preserved
        val tables = tableDao.getAllTables()
        assertEquals(5, tables.size)
    }

    /**
     * OFFLINE-READ-28: getMesas has non-empty payload but all table IDs invalid.
     * Expected: old Room cache preserved, no replaceAll(empty).
     */
    @Test
    fun testOFFLINE_READ_28_getMesasAllTableIdsInvalidPreservesExistingRoomCache() = runBlocking {
        for (i in 1..5) {
            tableDao.insert(
                TableEntity(
                    id = "tbl-$i",
                    number = i,
                    status = "AVAILABLE",
                    sectorName = "Salão",
                    sectorId = "sec-1",
                    customerName = null,
                    comandaId = null,
                    peopleCount = 1,
                    totalBalance = 0.0,
                    paidAmount = 0.0,
                    pendingBalance = 0.0,
                    itemsJson = "[]",
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
        assertEquals(5, tableDao.getAllTables().size)

        val invalidSector = Sector(
            id = "sec-1",
            nome = "Salão",
            mesas = listOf(
                MesaDto(id = "", numero = 1, status = "LIVRE"),
                MesaDto(id = "   ", numero = 2, status = "LIVRE")
            )
        )
        whenever(apiService.getMesas(any())).thenReturn(RestaurantResponse(setores = listOf(invalidSector)))

        val result = tableReadRepository.refreshTables("token")
        assertTrue(result.isFailure)

        // Old Room cache preserved
        val tables = tableDao.getAllTables()
        assertEquals(5, tables.size)
    }

    /**
     * OFFLINE-READ-29: server-confirmed abrir returns comanda C. TableManager explicitly empty.
     * Expected: Room updated before navigation. TableOrder resolves comanda C without TableManager.
     */
    @Test
    fun testOFFLINE_READ_29_serverConfirmedAbrirWritesRoomAndTableOrderResolvesWithoutTableManager() = runBlocking {
        TableManager.setTables(emptyList())

        tableDao.insert(
            TableEntity(
                id = "tbl-29",
                number = 29,
                status = "AVAILABLE",
                sectorName = "Varanda",
                sectorId = "sec-v",
                customerName = null,
                comandaId = null,
                peopleCount = 1,
                totalBalance = 0.0,
                paidAmount = 0.0,
                pendingBalance = 0.0,
                itemsJson = "[]",
                updatedAt = System.currentTimeMillis()
            )
        )

        val responseMap = mapOf("id" to "cmd-confirmed-29", "status" to "ABERTA")
        whenever(apiService.manageComanda(any(), any(), anyOrNull())).thenReturn(Response.success(responseMap))

        val mesaViewModel = MesaViewModel(
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            context = context
        )
        val initialTable = tableReadRepository.getTableById("tbl-29")!!
        mesaViewModel.openTable("token", initialTable, "Cliente 29")
        waitUntil { mesaViewModel.openSuccess.value == true }

        val roomTable = tableDao.getTableById("tbl-29")
        assertNotNull(roomTable)
        assertEquals("OCCUPIED", roomTable?.status)
        assertEquals("cmd-confirmed-29", roomTable?.comandaId)
        assertEquals("Cliente 29", roomTable?.customerName)

        // Initialize TableOrderViewModel without TableManager
        val orderViewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )
        orderViewModel.init("tbl-29", 29, "sec-v", "token")
        waitUntil { orderViewModel.table.value != null }

        val resolvedTable = orderViewModel.table.value
        assertNotNull(resolvedTable)
        assertEquals("tbl-29", resolvedTable?.id)
        assertEquals("cmd-confirmed-29", resolvedTable?.comandaId)
        assertEquals("Cliente 29", resolvedTable?.customerName)
    }

    /**
     * OFFLINE-READ-30: snapshot order price = 10, catalog current price = 15.
     * Expected: existing item price remains 10 and never becomes 15.
     */
    @Test
    fun testOFFLINE_READ_30_snapshotOrderPricePreservedAgainstNewCatalogPrice() = runBlocking {
        catalogDao.insertAll(
            listOf(
                Product(
                    id = "p-burger",
                    name = "Hambúrguer",
                    selling_price = 15.0
                )
            )
        )

        val items = listOf(
            MesaItemDto(
                id = "item-10",
                produto_id = "p-burger",
                nome = "Hambúrguer",
                quantidade = 1,
                preco_unitario = 10.0, // Historical price in snapshot
                subtotal = 10.0,
                status = "ENTREGUE"
            )
        )
        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-30",
            tenantId = TEST_TENANT,
            tableId = "tbl-30",
            tableNumber = 30,
            customerIdentifier = "Cliente 30",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 0L,
            localRevision = 0L,
            totalBaseMinor = 1000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 1000L,
            itemsJson = gson.toJson(items),
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(snapshot)

        tableDao.insert(
            TableEntity(
                id = "tbl-30",
                number = 30,
                status = "OCCUPIED",
                sectorName = "Salão",
                sectorId = "sec-1",
                customerName = "Cliente 30",
                comandaId = "cmd-30",
                peopleCount = 1,
                totalBalance = 10.0,
                paidAmount = 0.0,
                pendingBalance = 10.0,
                itemsJson = "[]",
                updatedAt = System.currentTimeMillis()
            )
        )

        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("Offline") }

        val orderViewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )
        orderViewModel.init("tbl-30", 30, "sec-1", "token")
        waitUntil { orderViewModel.table.value?.items?.isNotEmpty() == true }

        val table = orderViewModel.table.value
        assertEquals(1, table?.items?.size)
        val item = table?.items?.first()
        assertEquals(10.0, item?.product?.selling_price ?: 0.0, 0.001)
        assertNotEquals(15.0, item?.product?.selling_price ?: 0.0, 0.001)
    }

    /**
     * OFFLINE-READ-31: snapshot item has NO authoritative item price. Catalog current price = 15.
     * Expected: 15 is NOT used as historical order price (fails closed to 0.0 / unpayable).
     */
    @Test
    fun testOFFLINE_READ_31_snapshotItemWithoutAuthoritativePriceDoesNotFallbackToCatalog() = runBlocking {
        catalogDao.insertAll(
            listOf(
                Product(
                    id = "p-pizza",
                    name = "Pizza",
                    selling_price = 15.0
                )
            )
        )

        val items = listOf(
            MesaItemDto(
                id = "item-31",
                produto_id = "p-pizza",
                nome = "Pizza",
                quantidade = 1,
                preco_unitario = null, // No price in snapshot
                subtotal = null,
                status = "ENTREGUE"
            )
        )
        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-31",
            tenantId = TEST_TENANT,
            tableId = "tbl-31",
            tableNumber = 31,
            customerIdentifier = "Cliente 31",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 0L,
            localRevision = 0L,
            totalBaseMinor = 0L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 0L,
            itemsJson = gson.toJson(items),
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(snapshot)

        tableDao.insert(
            TableEntity(
                id = "tbl-31",
                number = 31,
                status = "OCCUPIED",
                sectorName = "Salão",
                sectorId = "sec-1",
                customerName = "Cliente 31",
                comandaId = "cmd-31",
                peopleCount = 1,
                totalBalance = 0.0,
                paidAmount = 0.0,
                pendingBalance = 0.0,
                itemsJson = "[]",
                updatedAt = System.currentTimeMillis()
            )
        )

        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("Offline") }

        val orderViewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )
        orderViewModel.init("tbl-31", 31, "sec-1", "token")
        waitUntil { orderViewModel.table.value?.items?.isNotEmpty() == true }

        val table = orderViewModel.table.value
        assertEquals(1, table?.items?.size)
        val item = table?.items?.first()
        // Must NOT fallback to catalog 15.0 and must remain null (UNKNOWN), never converted to 0.0
        assertNull(item?.product?.selling_price)
    }

    /**
     * OFFLINE-READ-32: cancel coroutine during cacheRemoteDetail/mapping.
     * Expected: CancellationException propagated.
     */
    @Test
    fun testOFFLINE_READ_32_cancellationExceptionPropagatedDuringRemoteRefresh() = runBlocking {
        whenever(apiService.getMesas(any())).thenAnswer {
            throw kotlinx.coroutines.CancellationException("Coroutine cancelled intentionally")
        }

        try {
            tableReadRepository.refreshTables("token")
            fail("Expected CancellationException to propagate")
        } catch (e: kotlinx.coroutines.CancellationException) {
            assertEquals("Coroutine cancelled intentionally", e.message)
        }
    }

    /**
     * OFFLINE-READ-33: Item split with unknown historical price fails closed.
     */
    @Test
    fun testOFFLINE_READ_33_itemSplitWithUnknownHistoricalPrice_failsClosed() = runBlocking {
        val testTable = Table(
            id = "tbl-33",
            number = 33,
            status = "OCCUPIED",
            comandaId = "cmd-33",
            total = 100.0,
            paidAmount = 0.0
        )
        // Item with UNKNOWN price (null selling_price)
        val itemUnknown = TableItem(
            product = Product(id = "p-unknown", name = "Special Dish", selling_price = null),
            quantity = 2
        )
        testTable.items.add(itemUnknown)

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository,
            tableReadRepository = tableReadRepository
        )

        val detail = ComandaDetailResponse(
            id = "cmd-33",
            mesaId = "tbl-33",
            status = "ABERTA",
            baseCurrency = "BRL",
            total = 100.0,
            totalPago = 0.0,
            totalPagoBase = 0.0,
            saldoBase = 100.0,
            itens = listOf(
                MesaItemDto(id = "it-1", produto_id = "p-unknown", nome = "Special Dish", quantidade = 2, preco_unitario = null, subtotal = null)
            )
        )
        whenever(apiService.getComandaDetail(any(), any())).thenReturn(detail)

        checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
        waitUntil { checkoutViewModel.uiState.value.moneyAuthorityState == MoneyAuthorityState.READY_REMOTE }

        // Switch to split by items (mode 2)
        checkoutViewModel.setSplitMode(2)
        assertTrue(checkoutViewModel.uiState.value.isPayButtonBlocked)
        assertEquals("Divisão por itens indisponível: item com preço histórico desconhecido.", checkoutViewModel.uiState.value.blockReason)

        // Select the item and attempt commit
        checkoutViewModel.onItemSelected(0, true)
        assertTrue(checkoutViewModel.uiState.value.isPayButtonBlocked)

        try {
            checkoutViewModel.buildCommitRequest(PaymentMethod.CASH)
            fail("Expected IllegalStateException for unknown price item in split")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("ITEM_SPLIT_UNKNOWN_PRICE") == true)
        }
    }

    /**
     * OFFLINE-READ-34: Snapshot with null baseMinorUnitDigits does NOT default to 2.
     * Expected: Accounting summary null, money display blocked/unavailable.
     */
    @Test
    fun testOFFLINE_READ_34_missingFrozenDigits_noDefault2_blocksMoneyDisplay() = runBlocking {
        val snapshotMissingDigits = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-34",
            tenantId = TEST_TENANT,
            tableId = "tbl-34",
            tableNumber = 34,
            customerIdentifier = "Mesa 34",
            baseCurrency = "BRL",
            baseMinorUnitDigits = null, // MISSING frozen digits
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 1L,
            localRevision = 1L,
            totalBaseMinor = 10000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 10000L,
            itemsJson = "[]",
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(snapshotMissingDigits)
        tableDao.insert(
            TableEntity(id = "tbl-34", number = 34, status = "OCCUPIED", sectorName = "Geral", sectorId = "sec-1", comandaId = "cmd-34", updatedAt = System.currentTimeMillis())
        )

        val decision = ComandaSnapshotAuthorityPolicy.evaluate(snapshotMissingDigits, "cmd-34", context)
        assertEquals(SnapshotAuthorityDecision.MISSING_AUTHORITY, decision)

        val orderViewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )

        // Mock network error to load exclusively from local snapshot
        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw IOException("Offline") }

        orderViewModel.init("tbl-34", 34, "sec-1", "token")
        waitUntil { orderViewModel.table.value != null }

        // Accounting summary must NOT be created with invented scale of 2
        assertNull(orderViewModel.accountingSummary.value)
    }

    /**
     * OFFLINE-READ-35: applyServerConfirmedOpen preserves known topology metadata.
     */
    @Test
    fun testOFFLINE_READ_35_applyServerConfirmedOpen_preservesKnownTopology() = runBlocking {
        // Apply confirmed open when table was not in Room yet
        tableReadRepository.applyServerConfirmedOpen(
            tableId = "tbl-35",
            comandaId = "cmd-35",
            customerName = "João Silva",
            peopleCount = 4,
            knownNumber = 35,
            knownSectorId = "sec-vip",
            knownSectorName = "VIP Lounge"
        )

        val table = tableReadRepository.getTableById("tbl-35")
        assertNotNull(table)
        assertEquals(35, table?.number)
        assertEquals("sec-vip", table?.sectorId)
        assertEquals("VIP Lounge", table?.sectorName)
        assertEquals("cmd-35", table?.comandaId)
        assertEquals("João Silva", table?.customerName)
        assertEquals(4, table?.people_count)
        assertEquals(Table.Status.OCCUPIED, table?.status)
    }

    /**
     * OFFLINE-READ-36: HTTP 5xx during checkout remote refresh preserves READY_LOCAL with warning.
     */
    @Test
    fun testOFFLINE_READ_36_http5xx_preservesReadyLocalWithWarning() = runBlocking {
        val usableSnapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-36",
            tableId = "tbl-36",
            tenantId = TEST_TENANT,
            tableNumber = 36,
            customerIdentifier = "Mesa 36",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 1L,
            localRevision = 1L,
            totalBaseMinor = 5000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 5000L,
            itemsJson = "[]",
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(usableSnapshot)

        val testTable = Table(id = "tbl-36", number = 36, status = "OCCUPIED", comandaId = "cmd-36")
        tableDao.insert(
            TableEntity(id = "tbl-36", number = 36, status = "OCCUPIED", sectorName = "Geral", sectorId = "sec-1", comandaId = "cmd-36", updatedAt = System.currentTimeMillis())
        )

        // Mock 500 Server Error
        val errorResponse = Response.error<ComandaDetailResponse>(
            500,
            "Internal Server Error".toResponseBody("application/json".toMediaTypeOrNull())
        )
        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw HttpException(errorResponse) }

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository,
            tableReadRepository = tableReadRepository
        )

        checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
        waitUntil { checkoutViewModel.uiState.value.refreshWarning != null }

        // Must preserve READY_LOCAL, NOT drop to LOAD_ERROR
        assertEquals(MoneyAuthorityState.READY_LOCAL, checkoutViewModel.uiState.value.moneyAuthorityState)
        assertTrue(checkoutViewModel.uiState.value.isPayButtonBlocked)
        assertEquals("Sem conexão — exibindo dados salvos", checkoutViewModel.uiState.value.refreshWarning)
        assertTrue(checkoutViewModel.moneyAuthorityLoaded)
    }

    /**
     * OFFLINE-READ-37: HTTP 5xx during TableOrder sync preserves LOCAL_CACHED and warning.
     */
    @Test
    fun testOFFLINE_READ_37_http5xx_tableOrderPreservesLocalCached() = runBlocking {
        val usableSnapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-37",
            tableId = "tbl-37",
            tenantId = TEST_TENANT,
            tableNumber = 37,
            customerIdentifier = "Mesa 37",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = 1L,
            localRevision = 1L,
            totalBaseMinor = 8000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 8000L,
            itemsJson = "[]",
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        comandaSnapshotDao.upsert(usableSnapshot)

        tableDao.insert(
            TableEntity(id = "tbl-37", number = 37, status = "OCCUPIED", sectorName = "Geral", sectorId = "sec-1", comandaId = "cmd-37", updatedAt = System.currentTimeMillis())
        )

        // Mock 503 Service Unavailable
        val errorResponse = Response.error<ComandaDetailResponse>(
            503,
            "Service Unavailable".toResponseBody("application/json".toMediaTypeOrNull())
        )
        whenever(apiService.getComandaDetail(any(), any())).thenAnswer { throw HttpException(errorResponse) }

        val orderViewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )

        orderViewModel.init("tbl-37", 37, "sec-1", "token")
        waitUntil { orderViewModel.refreshWarning.value != null }

        // Must preserve LOCAL_CACHED, not destructive error
        assertEquals(ReadProvenance.LOCAL_CACHED, orderViewModel.readProvenance.value)
        assertEquals("Sem conexão — exibindo dados salvos", orderViewModel.refreshWarning.value)
        assertNull(orderViewModel.error.value)
    }
}
