package com.plugpdv.pdv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.*
import com.plugpdv.pdv.models.ComandaDetailResponse
import com.plugpdv.pdv.models.RestaurantResponse
import com.plugpdv.pdv.repository.ComandaSnapshotRepository
import com.plugpdv.pdv.repository.TableReadRepository
import com.plugpdv.pdv.util.SaleModeUtil
import com.plugpdv.pdv.ui.sale.MesaViewModel
import com.plugpdv.pdv.ui.sale.TableOrderViewModel
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.HttpErrorParser
import com.plugpdv.pdv.utils.TenantBindingStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper
import retrofit2.HttpException
import retrofit2.Response
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
class OperationModeAnd403SemanticsTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var apiService: PosApiService
    private lateinit var catalogDao: CatalogDao
    private lateinit var tableDao: TableDao
    private lateinit var comandaSnapshotDao: ComandaSnapshotDao
    private lateinit var tableReadRepository: TableReadRepository
    private lateinit var comandaSnapshotRepository: ComandaSnapshotRepository
    private val gson = Gson()

    private val TEST_TENANT = "tenant-mode-test"

    private suspend fun waitUntil(timeoutMs: Long = 4000, condition: () -> Boolean) {
        val start = System.currentTimeMillis()
        while (!condition() && (System.currentTimeMillis() - start) < timeoutMs) {
            ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
            delay(50)
        }
        ShadowLooper.idleMainLooper(100, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TenantBindingStore.setActiveTenantId(context, TEST_TENANT)

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
        TenantBindingStore.clearTenant(context)
        db.close()
    }

    // =========================================================================
    // 1. 403 SEMANTICS UNIT TESTS
    // =========================================================================

    @Test
    fun test403Semantics_deviceBlocked_returnsDeviceBlockedMessage() {
        val body = "{\"error\":\"DEVICE_BLOCKED\"}"
        val msg = HttpErrorParser.parse403Message(body)
        assertEquals("Dispositivo bloqueado pelo administrador.", msg)
        assertFalse(msg.contains("Terminal bloqueado. Contate o suporte."))
    }

    @Test
    fun test403Semantics_userBlocked_returnsUserBlockedMessage() {
        val body = "{\"error\":\"USER_BLOCKED\"}"
        val msg = HttpErrorParser.parse403Message(body)
        assertEquals("Acesso revogado.", msg)
    }

    @Test
    fun test403Semantics_deviceNotRegistered_returnsNotRegisteredMessage() {
        val body = "{\"error\":\"device_not_registered\"}"
        val msg = HttpErrorParser.parse403Message(body)
        assertEquals("Este terminal não está registrado. Chame o suporte.", msg)
    }

    @Test
    fun test403Semantics_deviceOwnerMismatch_returnsMismatchMessage() {
        val body = "{\"error\":\"device_owner_mismatch\"}"
        val msg = HttpErrorParser.parse403Message(body)
        assertEquals("Este terminal já está registrado para outra empresa. Limpe os dados do app ou recadastre o terminal.", msg)
    }

    @Test
    fun test403Semantics_comandaModeDisabled_returnsComandaDisabledMessage() {
        val body = "{\"error\":\"Comanda mode is disabled for this user\"}"
        val msg = HttpErrorParser.parse403Message(body)
        assertEquals("O modo Comandas está desabilitado para este usuário.", msg)
        assertFalse(msg.contains("Terminal bloqueado"))
    }

    @Test
    fun test403Semantics_mesaModeDisabled_returnsMesaDisabledMessage() {
        val body = "{\"error\":\"Mesa mode is disabled for this user\"}"
        val msg = HttpErrorParser.parse403Message(body)
        assertEquals("O modo Mesas está desabilitado para este usuário.", msg)
        assertFalse(msg.contains("Terminal bloqueado"))
    }

    @Test
    fun test403Semantics_vendaDiretaModeDisabled_returnsVendaDisabledMessage() {
        val body = "{\"error\":\"Venda direta mode is disabled for this user\"}"
        val msg = HttpErrorParser.parse403Message(body)
        assertEquals("O modo Venda Rápida está desabilitado para este usuário.", msg)
        assertFalse(msg.contains("Terminal bloqueado"))
    }

    @Test
    fun test403Semantics_genericOperationModeDisabledWithDefaultMode_returnsModeSpecificMessage() {
        val body = "{\"error\":\"OPERATION_MODE_DISABLED\"}"
        assertEquals("O modo Mesas está desabilitado para este usuário.", HttpErrorParser.parse403Message(body, defaultMode = "mesa"))
        assertEquals("O modo Comandas está desabilitado para este usuário.", HttpErrorParser.parse403Message(body, defaultMode = "comanda"))
        assertEquals("O modo Venda Rápida está desabilitado para este usuário.", HttpErrorParser.parse403Message(body, defaultMode = "venda_direta"))
    }

    @Test
    fun test403Semantics_genericUnknown403_neverClaimsTerminalBlocked() {
        val genericBody = "{\"error\":\"Forbidden\"}"
        val msg = HttpErrorParser.parse403Message(genericBody)
        assertEquals("Acesso não autorizado para esta operação.", msg)
        assertFalse(msg.contains("Terminal bloqueado"))

        val nullBodyMsg = HttpErrorParser.parse403Message(null)
        assertEquals("Acesso não autorizado para esta operação.", nullBodyMsg)

        val emptyBodyMsg = HttpErrorParser.parse403Message("")
        assertEquals("Acesso não autorizado para esta operação.", emptyBodyMsg)
    }

    // =========================================================================
    // 2. VIEWMODEL 403 INTEGRATION TESTS
    // =========================================================================

    @Test
    fun testTableOrderViewModel_403ComandaDisabled_presentsSemanticErrorNotTerminalBlocked() = runBlocking {
        val tableEntity = TableEntity(
            id = "tbl-1",
            number = 1,
            status = "OCCUPIED",
            sectorName = "Salao",
            sectorId = "sec-1",
            comandaId = "cmd-1"
        )
        tableDao.insert(tableEntity)

        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-1",
            tenantId = TEST_TENANT,
            tableId = "tbl-1",
            tableNumber = 1,
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

        whenever(apiService.getComandaDetail(any(), any())).thenAnswer {
            throw HttpException(
                Response.error<ComandaDetailResponse>(
                    403,
                    "{\"error\":\"Comanda mode is disabled for this user\"}".toResponseBody("application/json".toMediaTypeOrNull())
                )
            )
        }

        val viewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )
        viewModel.table.observeForever { }
        viewModel.error.observeForever { }

        viewModel.init("tbl-1", 1, "sec-1", "token-test")

        waitUntil { viewModel.error.value != null }

        assertNotNull(viewModel.error.value)
        assertEquals("O modo Comandas está desabilitado para este usuário.", viewModel.error.value)
        assertFalse(viewModel.error.value!!.contains("Terminal bloqueado"))
    }

    @Test
    fun testTableOrderViewModel_403GenericForbidden_presentsUnauthorizedNotTerminalBlocked() = runBlocking {
        val tableEntity = TableEntity(
            id = "tbl-2",
            number = 2,
            status = "OCCUPIED",
            sectorName = "Salao",
            sectorId = "sec-1",
            comandaId = "cmd-2"
        )
        tableDao.insert(tableEntity)

        val snapshot = ComandaSnapshotEntity(
            localComandaId = UUID.randomUUID().toString(),
            serverComandaId = "cmd-2",
            tenantId = TEST_TENANT,
            tableId = "tbl-2",
            tableNumber = 2,
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

        whenever(apiService.getComandaDetail(any(), any())).thenAnswer {
            throw HttpException(
                Response.error<ComandaDetailResponse>(
                    403,
                    "{\"error\":\"Forbidden\"}".toResponseBody("application/json".toMediaTypeOrNull())
                )
            )
        }

        val viewModel = TableOrderViewModel(
            context = context,
            apiService = apiService,
            catalogDao = catalogDao,
            tableReadRepository = tableReadRepository,
            comandaSnapshotRepository = comandaSnapshotRepository
        )
        viewModel.table.observeForever { }
        viewModel.error.observeForever { }

        viewModel.init("tbl-2", 2, "sec-1", "token-test")

        waitUntil { viewModel.error.value != null }

        assertNotNull(viewModel.error.value)
        assertEquals("Acesso não autorizado para esta operação.", viewModel.error.value)
        assertFalse(viewModel.error.value!!.contains("Terminal bloqueado"))
    }

    @Test
    fun testMesaViewModel_403MesaDisabled_presentsMesaDisabledNotTerminalBlocked() = runBlocking {
        val tableEntity = TableEntity(
            id = "tbl-3",
            number = 3,
            status = "AVAILABLE",
            sectorName = "Salao",
            sectorId = "sec-1"
        )
        tableDao.insert(tableEntity)

        whenever(apiService.getMesas(any())).thenAnswer {
            throw HttpException(
                Response.error<RestaurantResponse>(
                    403,
                    "{\"error\":\"Mesa mode is disabled for this user\"}".toResponseBody("application/json".toMediaTypeOrNull())
                )
            )
        }

        val viewModel = MesaViewModel(apiService, catalogDao, tableReadRepository, context)
        viewModel.tables.observeForever { }
        viewModel.error.observeForever { }

        viewModel.fetchTables("token-test")

        waitUntil { viewModel.error.value != null }

        assertNotNull(viewModel.error.value)
        assertEquals("O modo Mesas está desabilitado para este usuário.", viewModel.error.value)
        assertFalse(viewModel.error.value!!.contains("Terminal bloqueado"))
    }

    // =========================================================================
    // 3. OPERATION MODE INDEPENDENCE MATRIX & FAIL-CLOSED NAVIGATION (HOTFIX-01C)
    // =========================================================================

    private fun computeVisibleTabs(hasMesa: Boolean, hasVendaDireta: Boolean, hasComanda: Boolean): List<String> {
        val titles = mutableListOf<String>()
        if (hasMesa) titles.add("Mesas")
        if (hasVendaDireta) titles.add("Venda Rápida")
        if (hasComanda) titles.add("Comandas")
        // HOTFIX-01C: No implicit fallback to Venda Rápida! Fail-closed empty list.
        return titles
    }

    @Test
    fun testModeMatrix_A_mesaTrue_vendaTrue_comandaFalse() {
        val hasMesa = true
        val hasVendaDireta = true
        val hasComanda = false

        val authorized = SaleModeUtil.getAuthorizedModes(hasMesa, hasVendaDireta, hasComanda)
        assertEquals(listOf(SaleModeUtil.AuthorizedMode.MESA, SaleModeUtil.AuthorizedMode.VENDA_DIRETA), authorized)

        val tabs = computeVisibleTabs(hasMesa, hasVendaDireta, hasComanda)
        assertEquals(listOf("Mesas", "Venda Rápida"), tabs)
        assertTrue(tabs.contains("Mesas"))
        assertTrue(tabs.contains("Venda Rápida"))
        assertFalse("Comanda must not appear when comanda=false", tabs.contains("Comandas"))
    }

    @Test
    fun testModeMatrix_B_mesaFalse_vendaTrue_comandaTrue() {
        val hasMesa = false
        val hasVendaDireta = true
        val hasComanda = true

        val authorized = SaleModeUtil.getAuthorizedModes(hasMesa, hasVendaDireta, hasComanda)
        assertEquals(listOf(SaleModeUtil.AuthorizedMode.VENDA_DIRETA, SaleModeUtil.AuthorizedMode.COMANDA), authorized)

        val tabs = computeVisibleTabs(hasMesa, hasVendaDireta, hasComanda)
        assertEquals(listOf("Venda Rápida", "Comandas"), tabs)
        assertFalse("Mesa must not appear when mesa=false", tabs.contains("Mesas"))
        assertTrue(tabs.contains("Venda Rápida"))
        assertTrue(tabs.contains("Comandas"))
    }

    @Test
    fun testModeMatrix_C_mesaTrue_vendaFalse_comandaTrue() {
        val hasMesa = true
        val hasVendaDireta = false
        val hasComanda = true

        val authorized = SaleModeUtil.getAuthorizedModes(hasMesa, hasVendaDireta, hasComanda)
        assertEquals(listOf(SaleModeUtil.AuthorizedMode.MESA, SaleModeUtil.AuthorizedMode.COMANDA), authorized)

        val tabs = computeVisibleTabs(hasMesa, hasVendaDireta, hasComanda)
        assertEquals(listOf("Mesas", "Comandas"), tabs)
        assertTrue(tabs.contains("Mesas"))
        assertFalse("Venda Rápida must not appear when venda=false", tabs.contains("Venda Rápida"))
        assertTrue(tabs.contains("Comandas"))
    }

    @Test
    fun testModeMatrix_D_allTrue() {
        val hasMesa = true
        val hasVendaDireta = true
        val hasComanda = true

        val authorized = SaleModeUtil.getAuthorizedModes(hasMesa, hasVendaDireta, hasComanda)
        assertEquals(3, authorized.size)

        val tabs = computeVisibleTabs(hasMesa, hasVendaDireta, hasComanda)
        assertEquals(listOf("Mesas", "Venda Rápida", "Comandas"), tabs)
        assertTrue(tabs.contains("Mesas"))
        assertTrue(tabs.contains("Venda Rápida"))
        assertTrue(tabs.contains("Comandas"))
    }

    @Test
    fun testModeMatrix_E_allFalse_zeroSaleFragments_zeroTabs_noVendaFallback() {
        val hasMesa = false
        val hasVendaDireta = false
        val hasComanda = false

        val authorized = SaleModeUtil.getAuthorizedModes(hasMesa, hasVendaDireta, hasComanda)
        assertTrue("Zero authorized modes when all flags false", authorized.isEmpty())

        val tabs = computeVisibleTabs(hasMesa, hasVendaDireta, hasComanda)
        assertTrue("Zero sale tabs when all modes are false — NO Venda Rápida fallback", tabs.isEmpty())
        assertEquals(0, tabs.size)
    }

    @Test
    fun testMissingPrefs_defaultsAllFalse_noSalesModeGranted() {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        val hasMesa = prefs.getBoolean(Constants.HAS_MESA, false)
        val hasVendaDireta = prefs.getBoolean(Constants.HAS_VENDA_DIRETA, false)
        val hasComanda = prefs.getBoolean(Constants.HAS_COMANDA, false)

        assertFalse("HAS_MESA must default to false when absent", hasMesa)
        assertFalse("HAS_VENDA_DIRETA must default to false when absent", hasVendaDireta)
        assertFalse("HAS_COMANDA must default to false when absent", hasComanda)

        val authorizedModes = SaleModeUtil.getAuthorizedModes(hasMesa, hasVendaDireta, hasComanda)
        assertTrue("Missing prefs must never grant sales mode", authorizedModes.isEmpty())

        val tabs = computeVisibleTabs(hasMesa, hasVendaDireta, hasComanda)
        assertTrue("Zero tabs and zero fragments when preferences are missing", tabs.isEmpty())
    }

    @Test
    fun testExplicitNoModeState_exactMessageSemantics() {
        val expectedNoModeMessage = "Nenhum modo de venda está habilitado para este usuário."
        assertEquals("Nenhum modo de venda está habilitado para este usuário.", expectedNoModeMessage)
    }

    @Test
    fun testComandaFalseDoesNotBlockMesaModeFlow() {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(Constants.HAS_MESA, true)
            .putBoolean(Constants.HAS_VENDA_DIRETA, true)
            .putBoolean(Constants.HAS_COMANDA, false)
            .apply()

        val hasMesa = prefs.getBoolean(Constants.HAS_MESA, false)
        val hasVendaDireta = prefs.getBoolean(Constants.HAS_VENDA_DIRETA, false)
        val hasComanda = prefs.getBoolean(Constants.HAS_COMANDA, false)

        assertTrue("Mesa mode must be enabled", hasMesa)
        assertTrue("Venda Direta must be enabled", hasVendaDireta)
        assertFalse("Comanda mode is disabled", hasComanda)

        val tabs = computeVisibleTabs(hasMesa, hasVendaDireta, hasComanda)
        assertTrue("Mesa tab must be present even when comanda is false", tabs.contains("Mesas"))
        assertTrue("Venda Rápida tab must be present", tabs.contains("Venda Rápida"))
        assertFalse("Comanda tab must not be present", tabs.contains("Comandas"))
    }
}
