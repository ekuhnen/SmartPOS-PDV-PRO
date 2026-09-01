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

        // 2. Executar a cadeia de migrações e validar que o schema resultante bate exatamente com a versão 11
        db = helper.runMigrationsAndValidate(
            TEST_DB,
            11,
            true,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5,
            AppDatabase.MIGRATION_5_6,
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9,
            AppDatabase.MIGRATION_9_10,
            AppDatabase.MIGRATION_10_11
        )

        val countCursorAfter = db.query("SELECT COUNT(*) FROM local_sales")
        assertTrue(countCursorAfter.moveToFirst())
        val countAfter = countCursorAfter.getInt(0)
        countCursorAfter.close()
        assertEquals("Quantidade de vendas na v11 deve continuar sendo exatamente 2", 2, countAfter)

        db.close()

        // 3. Abrir com o Room oficial da versão 11 e consultar os DAOs
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, TEST_DB)
            .addMigrations(
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11
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

        // 4. Testar persistência em comanda_snapshots via DAO na v11
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

        // 5. Testar persistência em comanda_mutations e comanda_local_items via DAO na v11
        val mutation = ComandaMutationEntity(
            id = "mut-inst-1",
            operationType = "OPEN_TABLE",
            tenantId = "ten-test",
            actorUserId = "user-1",
            deviceId = "dev-1",
            localComandaId = "loc-inst-1",
            tableId = "tbl-1",
            localItemId = null,
            payloadJson = "{\"action\":\"abrir\"}",
            resolvedPayloadJson = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            attemptCount = 0,
            lastAttemptAt = null,
            nextRetryAt = System.currentTimeMillis(),
            status = "PENDING",
            pauseReason = null,
            reconciliationReason = null,
            claimToken = null,
            claimedAt = null,
            lastErrorCode = null,
            messageKey = null
        )
        roomDb.comandaMutationDao().insert(mutation)
        val readMutation = roomDb.comandaMutationDao().getById("mut-inst-1")
        assertNotNull(readMutation)
        assertEquals("OPEN_TABLE", readMutation?.operationType)
        assertEquals("PENDING", readMutation?.status)

        val localItem = ComandaLocalItemEntity(
            localItemId = "item-inst-1",
            localComandaId = "loc-inst-1",
            tenantId = "ten-test",
            serverItemId = null,
            productId = "p-1",
            productNameSnapshot = "Produto Teste",
            quantity = 1,
            observation = "Sem observacao",
            commercialRevision = "rev-test",
            displayAmountScaled = 2500L,
            displayCurrency = "BRL",
            displayDecimals = 2,
            localStatus = "DRAFT",
            serverStatus = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            reconciliationReason = null
        )
        roomDb.comandaLocalItemDao().insert(localItem)
        val readItem = roomDb.comandaLocalItemDao().getByLocalItemId("item-inst-1")
        assertNotNull(readItem)
        assertEquals("Produto Teste", readItem?.productNameSnapshot)
        assertEquals("DRAFT", readItem?.localStatus)

        roomDb.close()
    }

    @Test
    fun testRoomMigrationFromVersion10To11PreservesExistingState() = runBlocking {
        val testDbName = "migration-test-10-to-11-db"

        // 1. Criar banco real de teste na versão 10 usando o schema oficial 10.json
        var db = helper.createDatabase(testDbName, 10)

        // Inserir dados representativos na v10
        db.execSQL("""
            INSERT INTO products (id, name, sku, barcode, category, selling_price, stock, image_url, price_currency, group_id, group_name)
            VALUES ('prod_10', 'Suco Natural', 'SKU10', '78910', 'Bebidas', 15.0, 100, NULL, 'BRL', 'grp1', 'Sucos')
        """.trimIndent())

        db.execSQL("""
            INSERT INTO tables (id, number, status, sectorName, sectorId, customerName, comandaId, peopleCount, totalBalance, paidAmount, pendingBalance, itemsJson, updatedAt)
            VALUES ('table_10', 12, 'OCCUPIED', 'Varanda', 'sec_v', 'Carlos', 'srv_cmd_99', 4, 120.0, 20.0, 100.0, '[]', 1700000000000)
        """.trimIndent())

        db.execSQL("""
            INSERT INTO local_sales (localId, apiId, timestamp, createdAt, updatedAt, total, currency, paymentMethod, operatorId, operatorName, sessionId, itemsJson, customerName, taxAmount, serviceFeeAmount, serviceFeeKind, convertedTotal, payloadJson, attemptCount, lastError, lastAttemptAt, syncStatus, syncedToApi, idempotencyKeyUsed)
            VALUES ('sale_10', 'api_sale_10', 1700000000000, 1700000000000, 1700000000000, 80.0, 'BRL', 'CARD', 'op1', 'João', 'sess_1', '[]', 'Consumidor Final', 0.0, 0.0, NULL, 0.0, '{}', 0, NULL, NULL, 'SYNCED', 1, 1)
        """.trimIndent())

        db.execSQL("""
            INSERT INTO outbox_operations (id, operationType, targetGroupKey, payloadJson, createdAt, idempotencyKey, serverSeq, attemptCount, lastAttemptAt, nextRetryAt, status, lastError, messageKey, isRetriable)
            VALUES ('outbox_10', 'MESA_CHECKOUT', 'table_10', '{}', 1700000000000, 'outbox_10', NULL, 0, NULL, 1700000000000, 'PENDING', NULL, NULL, 1)
        """.trimIndent())

        db.execSQL("""
            INSERT INTO payment_attempts (reference, idempotencyKey, nonce, amount, currency, status, startedAt, completedAt, paymentMethod, tableNumber, orderId, description, rawCallbackUri, paymentAppPaymentId, statusMessage)
            VALUES ('pay_10', 'pay_10', 'nonce_10', 8000, 'BRL', 'APPROVED', 1700000000000, 1700000000500, 'CREDIT', 12, 'ord_1', 'Pagamento Mesa 12', NULL, 'tx_123', 'SUCESSO')
        """.trimIndent())

        db.execSQL("""
            INSERT INTO comanda_snapshots (localComandaId, serverComandaId, tenantId, tableId, tableNumber, customerIdentifier, baseCurrency, baseMinorUnitDigits, serverStatus, localStatus, syncStatus, serverRevision, localRevision, totalBaseMinor, paidBaseMinor, balanceBaseMinor, itemsJson, paymentsJson, requiresReconciliation, reconciliationReason, serverUpdatedAt, cachedAt)
            VALUES ('loc_cmd_10', 'srv_cmd_99', 'ten_10', 'table_10', 12, 'Carlos', 'BRL', 2, 'ABERTA', 'OPEN', 'SYNCED', 1, 0, 12000, 2000, 10000, '[]', '[]', 0, NULL, 1700000000000, 1700000000000)
        """.trimIndent())

        db.close()

        // 2. Executar MIGRATION_10_11 e validar schema resultante v11
        db = helper.runMigrationsAndValidate(
            testDbName,
            11,
            true,
            AppDatabase.MIGRATION_10_11
        )

        // Validar dados preservados
        val curProd = db.query("SELECT * FROM products WHERE id = 'prod_10'")
        assertTrue(curProd.moveToFirst())
        assertEquals("Suco Natural", curProd.getString(curProd.getColumnIndexOrThrow("name")))
        val commRevIdx = curProd.getColumnIndexOrThrow("commercialRevision")
        assertTrue(curProd.isNull(commRevIdx))
        curProd.close()

        val curTable = db.query("SELECT * FROM tables WHERE id = 'table_10'")
        assertTrue(curTable.moveToFirst())
        assertEquals("Varanda", curTable.getString(curTable.getColumnIndexOrThrow("sectorName")))
        assertEquals(12, curTable.getInt(curTable.getColumnIndexOrThrow("number")))
        val localCmdIdx = curTable.getColumnIndexOrThrow("localComandaId")
        assertTrue(curTable.isNull(localCmdIdx))
        curTable.close()

        val curSale = db.query("SELECT COUNT(*) FROM local_sales")
        assertTrue(curSale.moveToFirst())
        assertEquals(1, curSale.getInt(0))
        curSale.close()

        val curOutbox = db.query("SELECT COUNT(*) FROM outbox_operations")
        assertTrue(curOutbox.moveToFirst())
        assertEquals(1, curOutbox.getInt(0))
        curOutbox.close()

        val curPay = db.query("SELECT COUNT(*) FROM payment_attempts")
        assertTrue(curPay.moveToFirst())
        assertEquals(1, curPay.getInt(0))
        curPay.close()

        val curSnap = db.query("SELECT COUNT(*) FROM comanda_snapshots")
        assertTrue(curSnap.moveToFirst())
        assertEquals(1, curSnap.getInt(0))
        curSnap.close()

        val curMut = db.query("SELECT COUNT(*) FROM comanda_mutations")
        assertTrue(curMut.moveToFirst())
        assertEquals(0, curMut.getInt(0))
        curMut.close()

        val curItems = db.query("SELECT COUNT(*) FROM comanda_local_items")
        assertTrue(curItems.moveToFirst())
        assertEquals(0, curItems.getInt(0))
        curItems.close()

        db.close()

        // 3. Abrir com o Room oficial da versão 11
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roomDb = Room.databaseBuilder(context, AppDatabase::class.java, testDbName)
            .addMigrations(
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5,
                AppDatabase.MIGRATION_5_6,
                AppDatabase.MIGRATION_6_7,
                AppDatabase.MIGRATION_7_8,
                AppDatabase.MIGRATION_8_9,
                AppDatabase.MIGRATION_9_10,
                AppDatabase.MIGRATION_10_11
            )
            .build()

        assertNotNull(roomDb)
        val product = roomDb.catalogDao().getProductById("prod_10")
        assertNotNull(product)
        assertEquals("Suco Natural", product?.name)
        assertNull(product?.commercialRevision)

        val table = roomDb.tableDao().getTableById("table_10")
        assertNotNull(table)
        assertEquals("Varanda", table?.sectorName)
        assertNull(table?.localComandaId)

        roomDb.close()
    }

    @Test
    fun testIdentityImmutabilityRejectsDuplicateInsert() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roomDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()

        // 1. Inserir ComandaMutationEntity inicial
        val mutation1 = ComandaMutationEntity(
            id = "K_IMMUTABLE_1",
            operationType = "OPEN_TABLE",
            tenantId = "ten_1",
            actorUserId = "user_1",
            deviceId = "dev_1",
            localComandaId = "loc_cmd_1",
            tableId = "tbl_1",
            localItemId = null,
            payloadJson = "{\"action\":\"open_original\"}",
            resolvedPayloadJson = null,
            createdAt = 1000L,
            updatedAt = 1000L,
            attemptCount = 0,
            lastAttemptAt = null,
            nextRetryAt = 1000L,
            status = "PENDING",
            pauseReason = null,
            reconciliationReason = null,
            claimToken = null,
            claimedAt = null,
            lastErrorCode = null,
            messageKey = null
        )
        roomDb.comandaMutationDao().insert(mutation1)

        val originalRead = roomDb.comandaMutationDao().getById("K_IMMUTABLE_1")
        assertNotNull(originalRead)
        assertEquals("{\"action\":\"open_original\"}", originalRead?.payloadJson)

        // 2. Tentar inserir duplicata com mesmo id K_IMMUTABLE_1 mas payload diferente
        val mutationDuplicate = ComandaMutationEntity(
            id = "K_IMMUTABLE_1",
            operationType = "OPEN_TABLE",
            tenantId = "ten_1",
            actorUserId = "user_1",
            deviceId = "dev_1",
            localComandaId = "loc_cmd_1",
            tableId = "tbl_1",
            localItemId = null,
            payloadJson = "{\"action\":\"MALICIOUS_OVERWRITE\"}",
            resolvedPayloadJson = null,
            createdAt = 2000L,
            updatedAt = 2000L,
            attemptCount = 0,
            lastAttemptAt = null,
            nextRetryAt = 2000L,
            status = "PENDING",
            pauseReason = null,
            reconciliationReason = null,
            claimToken = null,
            claimedAt = null,
            lastErrorCode = null,
            messageKey = null
        )

        var caughtMutationConflict = false
        try {
            roomDb.comandaMutationDao().insert(mutationDuplicate)
        } catch (e: Exception) {
            caughtMutationConflict = true
        }
        assertTrue("Inserir mutação com K duplicado deve lançar exceção de restrição", caughtMutationConflict)

        // Garantir que a linha original permaneceu inalterada
        val afterAttempt = roomDb.comandaMutationDao().getById("K_IMMUTABLE_1")
        assertNotNull(afterAttempt)
        assertEquals("{\"action\":\"open_original\"}", afterAttempt?.payloadJson)
        assertEquals(1000L, afterAttempt?.createdAt)

        // 3. Testar imutabilidade de ComandaLocalItemEntity
        val item1 = ComandaLocalItemEntity(
            localItemId = "LOC_ITEM_IMMUTABLE_1",
            localComandaId = "loc_cmd_1",
            tenantId = "ten_1",
            serverItemId = null,
            productId = "prod_1",
            productNameSnapshot = "Produto Original",
            quantity = 2,
            observation = null,
            commercialRevision = "rev_1",
            displayAmountScaled = 2000L,
            displayCurrency = "BRL",
            displayDecimals = 2,
            localStatus = "DRAFT",
            serverStatus = null,
            createdAt = 1000L,
            updatedAt = 1000L,
            reconciliationReason = null
        )
        roomDb.comandaLocalItemDao().insert(item1)

        val itemDuplicate = ComandaLocalItemEntity(
            localItemId = "LOC_ITEM_IMMUTABLE_1",
            localComandaId = "loc_cmd_1",
            tenantId = "ten_1",
            serverItemId = null,
            productId = "prod_1",
            productNameSnapshot = "Produto Sobrescrito",
            quantity = 5,
            observation = null,
            commercialRevision = "rev_2",
            displayAmountScaled = 5000L,
            displayCurrency = "BRL",
            displayDecimals = 2,
            localStatus = "DRAFT",
            serverStatus = null,
            createdAt = 2000L,
            updatedAt = 2000L,
            reconciliationReason = null
        )

        var caughtItemConflict = false
        try {
            roomDb.comandaLocalItemDao().insert(itemDuplicate)
        } catch (e: Exception) {
            caughtItemConflict = true
        }
        assertTrue("Inserir localItem com ID duplicado deve lançar exceção de restrição", caughtItemConflict)

        val itemAfterAttempt = roomDb.comandaLocalItemDao().getByLocalItemId("LOC_ITEM_IMMUTABLE_1")
        assertNotNull(itemAfterAttempt)
        assertEquals("Produto Original", itemAfterAttempt?.productNameSnapshot)
        assertEquals(2, itemAfterAttempt?.quantity)

        roomDb.close()
    }
}
