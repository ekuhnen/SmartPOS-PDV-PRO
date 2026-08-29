package com.plugpdv.pdv.database

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationAndroidTest {

    private val TEST_DB = "migration-test-db"

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun testRoomMigrationFromVersion3To9ValidatesSchemaWithoutMismatch() = runBlocking {
        // 1. Criar banco real de teste na versão 3 usando o schema oficial 3.json em assets
        var db = helper.createDatabase(TEST_DB, 3)

        // Insere Venda A (Sincronizada na v3)
        db.execSQL("""
            INSERT INTO local_sales (
                localId, apiId, timestamp, total, currency, paymentMethod,
                operatorId, operatorName, sessionId, itemsJson, syncedToApi
            ) VALUES (
                'sale_a_synced', 'api_999', 1700000000000, 200.0, 'BRL', 'MONEY',
                'op1', 'João', 'sess_1', '[{"product_id":"p1","product_name":"Item A","quantity":1,"price":200.0}]', 1
            )
        """.trimIndent())

        // Insere Venda B (Pendente na v3)
        db.execSQL("""
            INSERT INTO local_sales (
                localId, apiId, timestamp, total, currency, paymentMethod,
                operatorId, operatorName, sessionId, itemsJson, syncedToApi
            ) VALUES (
                'sale_b_pending', NULL, 1700000050000, 500.0, 'PYG', 'CARD',
                'op2', 'Maria', 'sess_2', '[{"product_id":"p2","product_name":"Item B","quantity":5,"price":100.0}]', 0
            )
        """.trimIndent())

        val countCursorBefore = db.query("SELECT COUNT(*) FROM local_sales")
        assertTrue(countCursorBefore.moveToFirst())
        val countBefore = countCursorBefore.getInt(0)
        countCursorBefore.close()
        assertEquals("Quantidade de vendas na v3 deve ser exatamente 2", 2, countBefore)

        db.close()

        // 2. Executar a cadeia de migrações e validar que o schema resultante bate exatamente com a versão 10
        db = helper.runMigrationsAndValidate(
            TEST_DB,
            10,
            true,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10
        )

        val countCursorAfter = db.query("SELECT COUNT(*) FROM local_sales")
        assertTrue(countCursorAfter.moveToFirst())
        val countAfter = countCursorAfter.getInt(0)
        countCursorAfter.close()
        assertEquals("Quantidade de vendas na v10 deve continuar sendo exatamente 2", 2, countAfter)

        db.close()

        // 3. Abrir com o Room oficial da versão 10 e consultar o DAO
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10
            )
            .build()

        assertNotNull(roomDb)
        val recentSales = roomDb.localSaleDao().getRecentSales()
        assertEquals(2, recentSales.size)

        val saleA = recentSales.first { it.localId == "sale_a_synced" }
        assertEquals("sale_a_synced", saleA.localId)
        assertEquals("api_999", saleA.apiId)
        assertEquals(200.0, saleA.total, 0.001)
        assertEquals("BRL", saleA.currency)
        assertEquals("MONEY", saleA.paymentMethod)
        assertEquals("op1", saleA.operatorId)
        assertEquals("João", saleA.operatorName)
        assertEquals("sess_1", saleA.sessionId)
        assertTrue(saleA.syncedToApi)
        assertEquals(LocalSaleEntity.STATUS_SYNCED, saleA.syncStatus)
        assertFalse(saleA.idempotencyKeyUsed) // Legado -> false

        val saleB = recentSales.first { it.localId == "sale_b_pending" }
        assertEquals("sale_b_pending", saleB.localId)
        assertNull(saleB.apiId)
        assertEquals(500.0, saleB.total, 0.001)
        assertEquals("PYG", saleB.currency)
        assertEquals("CARD", saleB.paymentMethod)
        assertEquals("op2", saleB.operatorId)
        assertEquals("Maria", saleB.operatorName)
        assertEquals("sess_2", saleB.sessionId)
        assertFalse(saleB.syncedToApi)
        assertEquals(LocalSaleEntity.STATUS_NEEDS_RECONCILIATION, saleB.syncStatus)
        assertEquals("{}", saleB.payloadJson)
        assertFalse(saleB.idempotencyKeyUsed) // Legado -> false

        // 4. Testar persistência em comanda_snapshots via DAO na v10
        val snapshot = ComandaSnapshotEntity(
            localComandaId = "loc-inst-1",
            serverComandaId = "srv-inst-1",
            tenantId = "ten-test",
            tableId = "tbl-1",
            tableNumber = 10,
            customerIdentifier = "Mesa 10",
            baseCurrency = "BRL",
            baseMinorUnitDigits = 2,
            serverStatus = "ABERTA",
            localStatus = "OPEN",
            syncStatus = "SYNCED",
            serverRevision = null,
            localRevision = 0L,
            totalBaseMinor = 15000L,
            paidBaseMinor = 5000L,
            balanceBaseMinor = 10000L,
            itemsJson = "[]",
            paymentsJson = "[]",
            requiresReconciliation = false,
            reconciliationReason = null,
            serverUpdatedAt = null,
            cachedAt = System.currentTimeMillis()
        )
        roomDb.comandaSnapshotDao().upsert(snapshot)
        val readBack = roomDb.comandaSnapshotDao().getByLocalId("loc-inst-1")
        assertNotNull(readBack)
        assertEquals("srv-inst-1", readBack?.serverComandaId)
        assertEquals(15000L, readBack?.totalBaseMinor)
        assertEquals(10000L, readBack?.balanceBaseMinor)

        roomDb.close()
    }
}
