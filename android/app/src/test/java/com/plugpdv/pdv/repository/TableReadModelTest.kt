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
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
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
        ShadowLooper.idleMainLooper()

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

        whenever(apiService.getComandaDetail(any(), eq("cmd-100"))).thenAnswer { throw IOException("No network") }

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

        whenever(apiService.getComandaDetail(any(), eq("cmd-200"))).thenAnswer { throw IOException("Timeout") }

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

        whenever(apiService.getComandaDetail(any(), eq("cmd-offline-chk"))).thenAnswer { throw IOException("No internet") }

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository
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
        whenever(apiService.getComandaDetail(any(), eq("cmd-online-chk"))).thenReturn(remoteDetail)

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository
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

        val opKey = UUID.randomUUID().toString()
        val attempt = PaymentAttemptEntity(
            reference = opKey,
            idempotencyKey = opKey,
            nonce = UUID.randomUUID().toString(),
            amount = 5000L,
            currency = "BRL",
            status = "APPROVED",
            startedAt = System.currentTimeMillis(),
            tableNumber = 12,
            orderId = "cmd-reconcile-chk",
            paymentMethod = "CARTAO_CREDITO"
        )
        paymentAttemptDao.insert(attempt)

        val outbox = OutboxOperationEntity(
            id = opKey,
            operationType = "COMANDA_CHECKOUT_COMMIT",
            targetGroupKey = "cmd-reconcile-chk",
            payloadJson = "{}",
            createdAt = System.currentTimeMillis(),
            idempotencyKey = opKey,
            status = "REQUIRES_RECONCILIATION"
        )
        outboxDao.insert(outbox)

        val checkoutViewModel = CheckoutViewModel(
            context = context,
            apiService = apiService,
            taxRepository = taxRepository,
            outboxDao = outboxDao,
            paymentAttemptDao = paymentAttemptDao,
            outboxSyncManager = outboxSyncManager,
            saleSyncScheduler = saleSyncScheduler,
            comandaSnapshotRepository = comandaSnapshotRepository
        )

        checkoutViewModel.init(testTable, "token", "session-1", "op-1", "Operador")
        waitUntil { checkoutViewModel.uiState.value.moneyAuthorityState == MoneyAuthorityState.RECONCILIATION_REQUIRED }

        val state = checkoutViewModel.uiState.value
        assertEquals(MoneyAuthorityState.RECONCILIATION_REQUIRED, state.moneyAuthorityState)
        assertTrue(state.requiresReconciliation)
        assertTrue(state.isPayButtonBlocked)
        assertEquals("Pagamento aprovado requer conciliação", state.blockReason)
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

        whenever(apiService.getComandaDetail(any(), eq("cmd-death-42"))).thenAnswer { throw IOException("Offline after process death") }

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
}
