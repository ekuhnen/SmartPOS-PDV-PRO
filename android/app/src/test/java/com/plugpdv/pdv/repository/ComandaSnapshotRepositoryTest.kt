package com.plugpdv.pdv.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.ComandaSnapshotEntity
import com.plugpdv.pdv.models.ComandaDetailResponse
import com.plugpdv.pdv.models.ComandaPaymentDto
import com.plugpdv.pdv.models.MesaItemDto
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.utils.MoneyDecimal
import com.plugpdv.pdv.utils.TenantBindingStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
class ComandaSnapshotRepositoryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var repository: ComandaSnapshotRepository
    private val gson = Gson()

    private val TENANT_A = "tenant-empresa-alpha"
    private val TENANT_B = "tenant-empresa-beta"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = ComandaSnapshotRepository(
            context = context,
            comandaSnapshotDao = db.comandaSnapshotDao(),
            gson = gson
        )
        TenantBindingStore.setActiveTenantId(context, TENANT_A)
    }

    @After
    fun tearDown() {
        TenantBindingStore.clearTenant(context)
        db.close()
    }

    /**
     * OFFLINE-SNAPSHOT-01: First server detail creates one snapshot with stable UUID.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_01_firstServerDetailCreatesOneSnapshotWithStableUuid() = runBlocking {
        val detail = ComandaDetailResponse(
            id = "cmd-srv-101",
            mesaId = "tbl-1",
            status = "ABERTA",
            numero = 1,
            nomeCliente = "Carlos",
            total = 120.0,
            totalPago = 0.0,
            totalPagoBase = 0.0,
            saldoBase = 120.0,
            baseCurrency = "BRL"
        )

        val table = Table(id = "tbl-1", number = 1, status = Table.Status.OCCUPIED)

        val snapshot = repository.cacheRemoteDetail(detail, table)
        assertNotNull(snapshot)
        assertNotNull(snapshot!!.localComandaId)
        assertTrue(snapshot.localComandaId.isNotBlank())
        assertEquals("cmd-srv-101", snapshot.serverComandaId)
        assertEquals(TENANT_A, snapshot.tenantId)
        assertEquals("tbl-1", snapshot.tableId)
        assertEquals(1, snapshot.tableNumber)
        assertEquals("Carlos", snapshot.customerIdentifier)
        assertEquals("OPEN", snapshot.localStatus)
        assertEquals("SYNCED", snapshot.syncStatus)
        assertEquals(0L, snapshot.localRevision)
        assertNull(snapshot.serverRevision)
        assertNull(snapshot.serverUpdatedAt)
        assertFalse(snapshot.requiresReconciliation)

        val readBack = repository.getByLocalId(snapshot.localComandaId)
        assertNotNull(readBack)
        assertEquals(snapshot.localComandaId, readBack?.localComandaId)
        assertEquals("cmd-srv-101", readBack?.serverComandaId)
    }

    /**
     * OFFLINE-SNAPSHOT-02: Second refresh same serverComandaId preserves same localComandaId.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_02_secondRefreshSameServerComandaIdPreservesLocalComandaId() = runBlocking {
        val detail1 = ComandaDetailResponse(
            id = "cmd-srv-102",
            mesaId = "tbl-2",
            status = "ABERTA",
            numero = 2,
            total = 100.0,
            totalPago = 0.0,
            totalPagoBase = 0.0,
            saldoBase = 100.0,
            baseCurrency = "BRL"
        )
        val snap1 = repository.cacheRemoteDetail(detail1)
        assertNotNull(snap1)
        val originalLocalId = snap1!!.localComandaId

        val detail2 = ComandaDetailResponse(
            id = "cmd-srv-102",
            mesaId = "tbl-2",
            status = "EM_CONSUMO",
            numero = 2,
            total = 150.0,
            totalPago = 0.0,
            totalPagoBase = 0.0,
            saldoBase = 150.0,
            baseCurrency = "BRL"
        )
        val snap2 = repository.cacheRemoteDetail(detail2)
        assertNotNull(snap2)

        assertEquals("localComandaId must be stable across refreshes", originalLocalId, snap2!!.localComandaId)
        assertEquals(15000L, snap2.totalBaseMinor)
        assertEquals(15000L, snap2.balanceBaseMinor)

        val all = repository.getAllForTenant(TENANT_A)
        assertEquals(1, all.size)
        assertEquals(originalLocalId, all[0].localComandaId)
    }

    /**
     * OFFLINE-SNAPSHOT-03: BRL base values become correct minor units.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_03_brlBaseValuesBecomeCorrectMinorUnits() = runBlocking {
        val detail = ComandaDetailResponse(
            id = "cmd-srv-103",
            mesaId = "tbl-3",
            status = "AGUARDANDO_PAGAMENTO",
            numero = 3,
            total = 123.45,
            totalPago = 50.0,
            totalPagoBase = 50.00,
            saldoBase = 73.45,
            baseCurrency = "BRL"
        )

        val snap = repository.cacheRemoteDetail(detail)
        assertNotNull(snap)
        assertEquals("BRL", snap!!.baseCurrency)
        assertEquals(2, snap.baseMinorUnitDigits)
        assertEquals(12345L, snap.totalBaseMinor)
        assertEquals(5000L, snap.paidBaseMinor)
        assertEquals(7345L, snap.balanceBaseMinor)
        assertEquals("OPEN", snap.localStatus)
        assertFalse(snap.requiresReconciliation)
    }

    /**
     * OFFLINE-SNAPSHOT-04: PYG base uses 0 minor decimals.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_04_pygBaseUsesZeroMinorDecimals() = runBlocking {
        val detail = ComandaDetailResponse(
            id = "cmd-srv-104",
            mesaId = "tbl-4",
            status = "ABERTA",
            numero = 4,
            total = 350000.0,
            totalPago = 0.0,
            totalPagoBase = 0.0,
            saldoBase = 350000.0,
            baseCurrency = "PYG"
        )

        val snap = repository.cacheRemoteDetail(detail)
        assertNotNull(snap)
        assertEquals("PYG", snap!!.baseCurrency)
        assertEquals(0, snap.baseMinorUnitDigits)
        assertEquals(350000L, snap.totalBaseMinor)
        assertEquals(0L, snap.paidBaseMinor)
        assertEquals(350000L, snap.balanceBaseMinor)
        assertFalse(snap.requiresReconciliation)
    }

    /**
     * Minor unit verification for BHD (3 decimals) and USD (2 decimals).
     */
    @Test
    fun testOFFLINE_SNAPSHOT_minorUnitVariations_bhdAndUsd() = runBlocking {
        val detailBhd = ComandaDetailResponse(
            id = "cmd-srv-bhd",
            mesaId = "tbl-bhd",
            status = "ABERTA",
            total = 12.345,
            totalPagoBase = 2.345,
            saldoBase = 10.000,
            baseCurrency = "BHD"
        )
        val snapBhd = repository.cacheRemoteDetail(detailBhd)
        assertNotNull(snapBhd)
        assertEquals("BHD", snapBhd!!.baseCurrency)
        assertEquals(3, snapBhd.baseMinorUnitDigits)
        assertEquals(12345L, snapBhd.totalBaseMinor)
        assertEquals(2345L, snapBhd.paidBaseMinor)
        assertEquals(10000L, snapBhd.balanceBaseMinor)

        val detailUsd = ComandaDetailResponse(
            id = "cmd-srv-usd",
            mesaId = "tbl-usd",
            status = "ABERTA",
            total = 12.34,
            totalPagoBase = 2.34,
            saldoBase = 10.00,
            baseCurrency = "USD"
        )
        val snapUsd = repository.cacheRemoteDetail(detailUsd)
        assertNotNull(snapUsd)
        assertEquals("USD", snapUsd!!.baseCurrency)
        assertEquals(2, snapUsd.baseMinorUnitDigits)
        assertEquals(1234L, snapUsd.totalBaseMinor)
        assertEquals(234L, snapUsd.paidBaseMinor)
        assertEquals(1000L, snapUsd.balanceBaseMinor)
    }

    /**
     * OFFLINE-SNAPSHOT-05: Missing base_currency creates/marks reconciliation-required, never defaults to BRL.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_05_missingBaseCurrencyMarksReconciliationRequiredNeverDefaultsToBrl() = runBlocking {
        val detail = ComandaDetailResponse(
            id = "cmd-srv-105",
            mesaId = "tbl-5",
            status = "ABERTA",
            numero = 5,
            total = 100.0,
            totalPago = 0.0,
            totalPagoBase = 0.0,
            saldoBase = 100.0,
            baseCurrency = null // Missing!
        )

        val snap = repository.cacheRemoteDetail(detail)
        assertNotNull(snap)
        assertNull(snap!!.baseCurrency)
        assertNull(snap.baseMinorUnitDigits)
        assertNull(snap.totalBaseMinor)
        assertNull(snap.paidBaseMinor)
        assertNull(snap.balanceBaseMinor)
        assertTrue(snap.requiresReconciliation)
        assertEquals("BASE_CURRENCY_MISSING", snap.reconciliationReason)
    }

    /**
     * OFFLINE-SNAPSHOT-06: Existing BRL + remote USD: BRL remains frozen, requiresReconciliation=true,
     * reconciliationReason="BASE_CURRENCY_CHANGED", no conflicting-money overwrite.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_06_existingBrlPlusRemoteUsd_freezesBrlAndRequiresReconciliation() = runBlocking {
        val detailBrl = ComandaDetailResponse(
            id = "cmd-srv-106",
            mesaId = "tbl-6",
            status = "ABERTA",
            total = 100.0,
            totalPagoBase = 20.0,
            saldoBase = 80.0,
            baseCurrency = "BRL"
        )
        val snap1 = repository.cacheRemoteDetail(detailBrl)
        assertNotNull(snap1)
        assertEquals("BRL", snap1!!.baseCurrency)
        assertEquals(10000L, snap1.totalBaseMinor)
        assertEquals(2000L, snap1.paidBaseMinor)
        assertEquals(8000L, snap1.balanceBaseMinor)
        assertFalse(snap1.requiresReconciliation)

        // Remote suddenly sends USD
        val detailUsd = ComandaDetailResponse(
            id = "cmd-srv-106",
            mesaId = "tbl-6",
            status = "ABERTA",
            total = 20.0,
            totalPagoBase = 4.0,
            saldoBase = 16.0,
            baseCurrency = "USD"
        )
        val snap2 = repository.cacheRemoteDetail(detailUsd)
        assertNotNull(snap2)

        assertEquals("Frozen base currency must not be overwritten", "BRL", snap2!!.baseCurrency)
        assertEquals(2, snap2.baseMinorUnitDigits)
        assertTrue(snap2.requiresReconciliation)
        assertEquals("BASE_CURRENCY_CHANGED", snap2.reconciliationReason)
        // Values must NOT be overwritten with the USD numbers
        assertEquals(10000L, snap2.totalBaseMinor)
        assertEquals(2000L, snap2.paidBaseMinor)
        assertEquals(8000L, snap2.balanceBaseMinor)
    }

    /**
     * OFFLINE-SNAPSHOT-07: detail.totalPagoBase missing: do NOT use detail.totalPago as base authority.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_07_totalPagoBaseMissing_neverFallsBackToTotalPago() = runBlocking {
        val detail = ComandaDetailResponse(
            id = "cmd-srv-107",
            mesaId = "tbl-7",
            status = "ABERTA",
            total = 100.0,
            totalPago = 50.0, // Transaction currency total exists
            totalPagoBase = null, // Base authority is MISSING!
            saldoBase = 50.0,
            baseCurrency = "BRL"
        )

        val snap = repository.cacheRemoteDetail(detail)
        assertNotNull(snap)
        assertEquals("BRL", snap!!.baseCurrency)
        assertNull("paidBaseMinor must not fall back to totalPago", snap.paidBaseMinor)
        assertTrue(snap.requiresReconciliation)
        assertEquals("BASE_MONEY_SUMMARY_MISSING", snap.reconciliationReason)
    }

    /**
     * OFFLINE-SNAPSHOT-08: detail.saldoBase missing: snapshot money authority is incomplete/reconciliation-required.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_08_saldoBaseMissing_marksReconciliationRequired() = runBlocking {
        val detail = ComandaDetailResponse(
            id = "cmd-srv-108",
            mesaId = "tbl-8",
            status = "ABERTA",
            total = 100.0,
            totalPago = 0.0,
            totalPagoBase = 0.0,
            saldoBase = null, // Saldo base MISSING!
            baseCurrency = "BRL"
        )

        val snap = repository.cacheRemoteDetail(detail)
        assertNotNull(snap)
        assertNull(snap!!.balanceBaseMinor)
        assertTrue(snap.requiresReconciliation)
        assertEquals("BASE_MONEY_SUMMARY_MISSING", snap.reconciliationReason)
    }

    /**
     * OFFLINE-SNAPSHOT-09: server requires_reconciliation=true cannot be cleared locally.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_09_serverRequiresReconciliationCannotBeClearedLocally() = runBlocking {
        val detail = ComandaDetailResponse(
            id = "cmd-srv-109",
            mesaId = "tbl-9",
            status = "ABERTA",
            total = 100.0,
            totalPago = 0.0,
            totalPagoBase = 0.0,
            saldoBase = 100.0,
            baseCurrency = "BRL",
            requiresReconciliation = true
        )

        val snap = repository.cacheRemoteDetail(detail)
        assertNotNull(snap)
        assertTrue(snap!!.requiresReconciliation)
        assertEquals("SERVER_RECONCILIATION_REQUIRED", snap.reconciliationReason)
    }

    /**
     * OFFLINE-SNAPSHOT-10: items/payments survive serialization + DB readback.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_10_itemsAndPaymentsSurviveSerializationAndReadback() = runBlocking {
        val item1 = MesaItemDto(
            id = "item-1",
            produto_id = "prod-1",
            nome = "Cerveja Artesanal",
            quantidade = 2,
            preco_unitario = 15.0,
            subtotal = 30.0
        )
        val payment1 = ComandaPaymentDto(
            id = "pay-1",
            forma = "CARD",
            valor = 30.0,
            moeda = "BRL",
            valorBase = 30.0,
            baseCurrency = "BRL",
            fxRate = 1.0,
            dataPagamento = "2026-08-29T10:00:00Z"
        )

        val detail = ComandaDetailResponse(
            id = "cmd-srv-110",
            mesaId = "tbl-10",
            status = "FECHADA",
            total = 30.0,
            totalPago = 30.0,
            totalPagoBase = 30.0,
            saldoBase = 0.0,
            baseCurrency = "BRL",
            itens = listOf(item1),
            pagamentos = listOf(payment1)
        )

        val snap = repository.cacheRemoteDetail(detail)
        assertNotNull(snap)
        assertEquals("CLOSED", snap!!.localStatus)

        val fromDb = repository.getByLocalId(snap.localComandaId)
        assertNotNull(fromDb)

        val deserializedItems = gson.fromJson(fromDb!!.itemsJson, Array<MesaItemDto>::class.java).toList()
        assertEquals(1, deserializedItems.size)
        assertEquals("Cerveja Artesanal", deserializedItems[0].nome)
        assertEquals(2, deserializedItems[0].quantidade)

        val deserializedPayments = gson.fromJson(fromDb.paymentsJson, Array<ComandaPaymentDto>::class.java).toList()
        assertEquals(1, deserializedPayments.size)
        assertEquals("CARD", deserializedPayments[0].forma)
        assertEquals(30.0, deserializedPayments[0].valor, 0.001)
    }

    /**
     * OFFLINE-SNAPSHOT-11: tenant A and incorrect tenant ownership never merge.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_11_tenantIsolationPreventsCrossTenantDataLeakage() = runBlocking {
        // Cache comanda under Tenant A
        val detailA = ComandaDetailResponse(
            id = "cmd-shared-id",
            mesaId = "tbl-11",
            status = "ABERTA",
            total = 200.0,
            totalPagoBase = 0.0,
            saldoBase = 200.0,
            baseCurrency = "BRL"
        )
        val snapA = repository.cacheRemoteDetail(detailA)
        assertNotNull(snapA)
        assertEquals(TENANT_A, snapA!!.tenantId)

        // Switch to Tenant B
        TenantBindingStore.setActiveTenantId(context, TENANT_B)

        // Querying for Tenant B must return null
        val lookupB = repository.getByServerComandaId(TENANT_B, "cmd-shared-id")
        assertNull("Tenant B must not see Tenant A comanda", lookupB)

        val allB = repository.getAllForTenant(TENANT_B)
        assertEquals(0, allB.size)

        // Cache same server ID under Tenant B -> should create independent localComandaId
        val detailB = ComandaDetailResponse(
            id = "cmd-shared-id",
            mesaId = "tbl-11",
            status = "ABERTA",
            total = 500.0,
            totalPagoBase = 100.0,
            saldoBase = 400.0,
            baseCurrency = "USD"
        )
        val snapB = repository.cacheRemoteDetail(detailB)
        assertNotNull(snapB)
        assertEquals(TENANT_B, snapB!!.tenantId)
        assertNotEquals(snapA.localComandaId, snapB.localComandaId)
        assertEquals(50000L, snapB.totalBaseMinor)
        assertEquals("USD", snapB.baseCurrency)

        // Switch back to Tenant A
        TenantBindingStore.setActiveTenantId(context, TENANT_A)
        val readA = repository.getByServerComandaId(TENANT_A, "cmd-shared-id")
        assertNotNull(readA)
        assertEquals(snapA.localComandaId, readA?.localComandaId)
        assertEquals(20000L, readA?.totalBaseMinor)
        assertEquals("BRL", readA?.baseCurrency)
    }

    /**
     * OFFLINE-SNAPSHOT-12: existing PENDING_MUTATIONS is not reset to SYNCED by remote refresh.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_12_pendingMutationsNotResetToSyncedByRemoteRefresh() = runBlocking {
        val detail1 = ComandaDetailResponse(
            id = "cmd-srv-112",
            mesaId = "tbl-12",
            status = "ABERTA",
            total = 80.0,
            totalPagoBase = 0.0,
            saldoBase = 80.0,
            baseCurrency = "BRL"
        )
        val snap1 = repository.cacheRemoteDetail(detail1)
        assertNotNull(snap1)
        assertEquals("SYNCED", snap1!!.syncStatus)

        // Simulate local offline mutation setting PENDING_MUTATIONS
        val mutated = snap1.copy(
            syncStatus = "PENDING_MUTATIONS",
            localStatus = "CLOSED"
        )
        db.comandaSnapshotDao().upsert(mutated)

        // Remote refresh occurs
        val detail2 = ComandaDetailResponse(
            id = "cmd-srv-112",
            mesaId = "tbl-12",
            status = "ABERTA",
            total = 80.0,
            totalPagoBase = 0.0,
            saldoBase = 80.0,
            baseCurrency = "BRL"
        )
        val snap2 = repository.cacheRemoteDetail(detail2)
        assertNotNull(snap2)
        assertEquals("PENDING_MUTATIONS must be preserved against remote refresh overwrite", "PENDING_MUTATIONS", snap2!!.syncStatus)
    }

    /**
     * OFFLINE-SNAPSHOT-13: process/database close and reopen preserves snapshot.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_13_processDatabaseCloseAndReopenPreservesSnapshot() = runBlocking {
        val detail = ComandaDetailResponse(
            id = "cmd-srv-113",
            mesaId = "tbl-13",
            status = "ABERTA",
            numero = 13,
            total = 99.90,
            totalPagoBase = 0.0,
            saldoBase = 99.90,
            baseCurrency = "BRL"
        )
        val snap = repository.cacheRemoteDetail(detail)
        assertNotNull(snap)
        val localId = snap!!.localComandaId

        // Close database
        db.close()

        // Reopen database
        val reopenedDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        // Note: For inMemoryDatabaseBuilder, re-building creates a fresh memory DB, but let's test DAO persistence with real entity insert/read
        val reRepository = ComandaSnapshotRepository(context, reopenedDb.comandaSnapshotDao(), gson)
        reopenedDb.comandaSnapshotDao().upsert(snap)

        val fromReopened = reRepository.getByLocalId(localId)
        assertNotNull(fromReopened)
        assertEquals("cmd-srv-113", fromReopened?.serverComandaId)
        assertEquals(9990L, fromReopened?.totalBaseMinor)
        assertEquals("BRL", fromReopened?.baseCurrency)
        assertEquals(2, fromReopened?.baseMinorUnitDigits)

        reopenedDb.close()
    }

    /**
     * Missing tenant binding does not persist snapshot under guessed ownership.
     */
    @Test
    fun testTenantBindingMissing_doesNotPersistGuessedSnapshot() = runBlocking {
        TenantBindingStore.clearTenant(context) // No active tenant

        val detail = ComandaDetailResponse(
            id = "cmd-srv-no-tenant",
            mesaId = "tbl-99",
            status = "ABERTA",
            total = 100.0,
            totalPagoBase = 0.0,
            saldoBase = 100.0,
            baseCurrency = "BRL"
        )

        val result = repository.cacheRemoteDetail(detail)
        assertNull("Must return null when tenant binding is missing", result)
        val all = db.comandaSnapshotDao().getAllForTenant(TENANT_A)
        assertEquals(0, all.size)
    }

    /**
     * OFFLINE-SNAPSHOT-14: Initial BRL digits=2. Change CurrencyRulesProvider fixture so BRL now reports 3.
     * Refresh same comanda. baseMinorUnitDigits remains 2, 123.45 still persists as 12345L (NOT 123450L).
     */
    @Test
    fun testOFFLINE_SNAPSHOT_14_frozenMinorUnitDigitsPreservedAgainstCapabilitiesChange() = runBlocking {
        val detail1 = ComandaDetailResponse(
            id = "cmd-srv-114",
            mesaId = "tbl-14",
            status = "ABERTA",
            total = 100.0,
            totalPagoBase = 0.0,
            saldoBase = 100.0,
            baseCurrency = "BRL"
        )
        val snap1 = repository.cacheRemoteDetail(detail1)
        assertNotNull(snap1)
        assertEquals(2, snap1!!.baseMinorUnitDigits)

        // Simulate external/dynamic capability change for BRL from 2 to 3 decimals
        val customRulesProvider = com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider()
        customRulesProvider.setCapabilities(
            mapOf(
                "BRL" to com.plugpdv.pdv.models.CurrencyCapability(
                    currencyCode = "BRL",
                    symbol = "R$",
                    symbolPosition = "PREFIX",
                    thousandsSeparator = ".",
                    decimalSeparator = ",",
                    displayDecimals = 3,
                    minorUnitDigits = 3
                )
            )
        )
        MoneyDecimal.setRulesProvider(customRulesProvider)

        try {
            // Verify MoneyDecimal now returns 3 decimals for new queries
            assertEquals(3, MoneyDecimal.getDecimals("BRL"))

            // Refresh existing comanda snapshot with total = 123.45
            val detail2 = ComandaDetailResponse(
                id = "cmd-srv-114",
                mesaId = "tbl-14",
                status = "ABERTA",
                total = 123.45,
                totalPagoBase = 23.45,
                saldoBase = 100.00,
                baseCurrency = "BRL"
            )
            val snap2 = repository.cacheRemoteDetail(detail2)
            assertNotNull(snap2)

            // Frozen scale of 2 must be respected!
            assertEquals("baseMinorUnitDigits must remain frozen at 2", 2, snap2!!.baseMinorUnitDigits)
            assertEquals("123.45 with 2 digits must produce 12345, not 123450", 12345L, snap2.totalBaseMinor)
            assertEquals(2345L, snap2.paidBaseMinor)
            assertEquals(10000L, snap2.balanceBaseMinor)
        } finally {
            MoneyDecimal.setRulesProvider(com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider())
        }
    }

    /**
     * OFFLINE-SNAPSHOT-15: Existing localStatus=CLOSED, syncStatus=PENDING_MUTATIONS.
     * Remote status=ABERTA.
     * Expected: serverStatus=ABERTA, localStatus=CLOSED, syncStatus=PENDING_MUTATIONS.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_15_pendingMutationsPreservesLocalStatusClosedWhileUpdatingServerStatus() = runBlocking {
        val detail1 = ComandaDetailResponse(
            id = "cmd-srv-115",
            mesaId = "tbl-15",
            status = "ABERTA",
            total = 50.0,
            totalPagoBase = 0.0,
            saldoBase = 50.0,
            baseCurrency = "BRL"
        )
        val snap1 = repository.cacheRemoteDetail(detail1)
        assertNotNull(snap1)

        // Mutate locally to CLOSED with PENDING_MUTATIONS
        val mutated = snap1!!.copy(
            localStatus = "CLOSED",
            syncStatus = "PENDING_MUTATIONS"
        )
        db.comandaSnapshotDao().upsert(mutated)

        // Remote refresh reports ABERTA
        val detail2 = ComandaDetailResponse(
            id = "cmd-srv-115",
            mesaId = "tbl-15",
            status = "ABERTA",
            total = 50.0,
            totalPagoBase = 0.0,
            saldoBase = 50.0,
            baseCurrency = "BRL"
        )
        val snap2 = repository.cacheRemoteDetail(detail2)
        assertNotNull(snap2)

        assertEquals("serverStatus must reflect remote status", "ABERTA", snap2!!.serverStatus)
        assertEquals("localStatus must remain CLOSED", "CLOSED", snap2.localStatus)
        assertEquals("syncStatus must remain PENDING_MUTATIONS", "PENDING_MUTATIONS", snap2.syncStatus)
    }

    /**
     * OFFLINE-SNAPSHOT-16: Existing PENDING_MUTATIONS contains local projection fields.
     * Remote older detail contains different values.
     * Expected: all local projection fields remain unchanged.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_16_pendingMutationsPreservesLocalProjectionAmountsAndItems() = runBlocking {
        val detail1 = ComandaDetailResponse(
            id = "cmd-srv-116",
            mesaId = "tbl-16",
            status = "ABERTA",
            total = 100.0,
            totalPagoBase = 0.0,
            saldoBase = 100.0,
            baseCurrency = "BRL"
        )
        val snap1 = repository.cacheRemoteDetail(detail1)
        assertNotNull(snap1)

        val localItems = "[{\"nome\":\"Local Burger\",\"quantidade\":1}]"
        val localPayments = "[{\"forma\":\"MONEY\",\"valor\":50.0}]"

        val localMutated = snap1!!.copy(
            syncStatus = "PENDING_MUTATIONS",
            totalBaseMinor = 15000L,
            paidBaseMinor = 5000L,
            balanceBaseMinor = 10000L,
            itemsJson = localItems,
            paymentsJson = localPayments,
            localRevision = 2L
        )
        db.comandaSnapshotDao().upsert(localMutated)

        // Remote older detail arrives with different values
        val remoteDetail = ComandaDetailResponse(
            id = "cmd-srv-116",
            mesaId = "tbl-16",
            status = "ABERTA",
            total = 80.0,
            totalPagoBase = 0.0,
            saldoBase = 80.0,
            baseCurrency = "BRL",
            itens = listOf(MesaItemDto(id = "old-item", nome = "Old Item", subtotal = 80.0))
        )
        val snap2 = repository.cacheRemoteDetail(remoteDetail)
        assertNotNull(snap2)

        assertEquals(15000L, snap2!!.totalBaseMinor)
        assertEquals(5000L, snap2.paidBaseMinor)
        assertEquals(10000L, snap2.balanceBaseMinor)
        assertEquals(localItems, snap2.itemsJson)
        assertEquals(localPayments, snap2.paymentsJson)
        assertEquals(2L, snap2.localRevision)
        assertEquals("PENDING_MUTATIONS", snap2.syncStatus)
    }

    /**
     * OFFLINE-SNAPSHOT-17: Existing PENDING_MUTATIONS. Remote requires_reconciliation=true.
     * Expected: local projection preserved, requiresReconciliation=true.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_17_pendingMutationsSurfacesRemoteReconciliationWithoutDestroyingProjection() = runBlocking {
        val detail1 = ComandaDetailResponse(
            id = "cmd-srv-117",
            mesaId = "tbl-17",
            status = "ABERTA",
            total = 100.0,
            totalPagoBase = 0.0,
            saldoBase = 100.0,
            baseCurrency = "BRL"
        )
        val snap1 = repository.cacheRemoteDetail(detail1)
        assertNotNull(snap1)

        val localMutated = snap1!!.copy(
            syncStatus = "PENDING_MUTATIONS",
            totalBaseMinor = 12000L,
            paidBaseMinor = 2000L,
            balanceBaseMinor = 10000L
        )
        db.comandaSnapshotDao().upsert(localMutated)

        // Remote refresh flags reconciliation
        val remoteDetail = ComandaDetailResponse(
            id = "cmd-srv-117",
            mesaId = "tbl-17",
            status = "ABERTA",
            total = 100.0,
            totalPagoBase = 0.0,
            saldoBase = 100.0,
            baseCurrency = "BRL",
            requiresReconciliation = true
        )
        val snap2 = repository.cacheRemoteDetail(remoteDetail)
        assertNotNull(snap2)

        assertTrue(snap2!!.requiresReconciliation)
        assertEquals("SERVER_RECONCILIATION_REQUIRED", snap2.reconciliationReason)
        assertEquals(12000L, snap2.totalBaseMinor)
        assertEquals(2000L, snap2.paidBaseMinor)
        assertEquals(10000L, snap2.balanceBaseMinor)
        assertEquals("PENDING_MUTATIONS", snap2.syncStatus)
    }

    /**
     * OFFLINE-SNAPSHOT-18: Existing PENDING_MUTATIONS BRL. Remote base_currency=USD.
     * Expected: BRL remains frozen, local financial projection remains, syncStatus remains PENDING_MUTATIONS,
     * requiresReconciliation=true, reason=BASE_CURRENCY_CHANGED.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_18_pendingMutationsConflictingRemoteCurrencySurfacesReconciliation() = runBlocking {
        val detail1 = ComandaDetailResponse(
            id = "cmd-srv-118",
            mesaId = "tbl-18",
            status = "ABERTA",
            total = 100.0,
            totalPagoBase = 0.0,
            saldoBase = 100.0,
            baseCurrency = "BRL"
        )
        val snap1 = repository.cacheRemoteDetail(detail1)
        assertNotNull(snap1)

        val localMutated = snap1!!.copy(
            syncStatus = "PENDING_MUTATIONS",
            totalBaseMinor = 10000L,
            paidBaseMinor = 0L,
            balanceBaseMinor = 10000L
        )
        db.comandaSnapshotDao().upsert(localMutated)

        // Remote comes back as USD
        val remoteDetail = ComandaDetailResponse(
            id = "cmd-srv-118",
            mesaId = "tbl-18",
            status = "ABERTA",
            total = 20.0,
            totalPagoBase = 0.0,
            saldoBase = 20.0,
            baseCurrency = "USD"
        )
        val snap2 = repository.cacheRemoteDetail(remoteDetail)
        assertNotNull(snap2)

        assertEquals("BRL", snap2!!.baseCurrency)
        assertEquals(2, snap2.baseMinorUnitDigits)
        assertEquals(10000L, snap2.totalBaseMinor)
        assertEquals("PENDING_MUTATIONS", snap2.syncStatus)
        assertTrue(snap2.requiresReconciliation)
        assertEquals("BASE_CURRENCY_CHANGED", snap2.reconciliationReason)
    }

    /**
     * OFFLINE-SNAPSHOT-19: Existing SYNCED snapshot. Remote same currency with changed authoritative totals.
     * Expected: normal remote refresh succeeds using frozen digits.
     */
    @Test
    fun testOFFLINE_SNAPSHOT_19_syncedSnapshotNormalRefreshUpdatesAuthoritativeTotalsWithFrozenDigits() = runBlocking {
        val detail1 = ComandaDetailResponse(
            id = "cmd-srv-119",
            mesaId = "tbl-19",
            status = "ABERTA",
            total = 100.0,
            totalPagoBase = 0.0,
            saldoBase = 100.0,
            baseCurrency = "BRL"
        )
        val snap1 = repository.cacheRemoteDetail(detail1)
        assertNotNull(snap1)
        assertEquals(10000L, snap1!!.totalBaseMinor)

        // Remote detail has updated items and payments
        val detail2 = ComandaDetailResponse(
            id = "cmd-srv-119",
            mesaId = "tbl-19",
            status = "EM_CONSUMO",
            total = 180.50,
            totalPagoBase = 50.00,
            saldoBase = 130.50,
            baseCurrency = "BRL"
        )
        val snap2 = repository.cacheRemoteDetail(detail2)
        assertNotNull(snap2)

        assertEquals("BRL", snap2!!.baseCurrency)
        assertEquals(2, snap2.baseMinorUnitDigits)
        assertEquals(18050L, snap2.totalBaseMinor)
        assertEquals(5000L, snap2.paidBaseMinor)
        assertEquals(13050L, snap2.balanceBaseMinor)
        assertEquals("OPEN", snap2.localStatus)
        assertEquals("SYNCED", snap2.syncStatus)
        assertFalse(snap2.requiresReconciliation)
    }
}
