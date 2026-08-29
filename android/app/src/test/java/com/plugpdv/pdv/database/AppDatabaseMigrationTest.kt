package com.plugpdv.pdv.database

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.DriverManager

class AppDatabaseMigrationTest {

    @Test
    fun testRealSqliteMigrationFromVersion3To9PreservesLegacySalesWithDefaultUnkeyed() {
        // Usa o motor SQLite JDBC real em memória (100% determinístico e independente de plataforma)
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val db = mock<SupportSQLiteDatabase>()

        whenever(db.execSQL(any())).thenAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            connection.createStatement().use { stmt ->
                stmt.execute(sql)
            }
            Unit
        }

        // 1. Criar o schema e a tabela local_sales na versão 3 (Origin main publicado)
        db.execSQL("""
            CREATE TABLE `local_sales` (
                `localId` TEXT NOT NULL,
                `apiId` TEXT,
                `timestamp` INTEGER NOT NULL,
                `total` REAL NOT NULL,
                `currency` TEXT NOT NULL,
                `paymentMethod` TEXT NOT NULL,
                `operatorId` TEXT,
                `operatorName` TEXT,
                `sessionId` TEXT,
                `itemsJson` TEXT NOT NULL,
                `syncedToApi` INTEGER NOT NULL,
                PRIMARY KEY(`localId`)
            )
        """.trimIndent())

        // Insere Venda A (Já sincronizada na v3)
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

        // 2. Executar a cadeia real de migrações SQLite (3 -> 4 -> 5 -> 6 -> 7 -> 8 -> 9)
        AppDatabase.MIGRATION_3_4.migrate(db)
        AppDatabase.MIGRATION_4_5.migrate(db)
        AppDatabase.MIGRATION_5_6.migrate(db)
        AppDatabase.MIGRATION_6_7.migrate(db)
        AppDatabase.MIGRATION_7_8.migrate(db)
        AppDatabase.MIGRATION_8_9.migrate(db)

        // 3. Validar no banco SQLite real a integridade das tabelas, preservação das vendas e idempotencyKeyUsed = 0 por padrão
        val stmtA = connection.createStatement()
        val rsA = stmtA.executeQuery("SELECT * FROM local_sales WHERE localId = 'sale_a_synced'")
        assertTrue("Venda A legada sincronizada deve existir após migração v3 -> v9", rsA.next())
        assertEquals("sale_a_synced", rsA.getString("localId"))
        assertEquals("api_999", rsA.getString("apiId"))
        assertEquals(1700000000000L, rsA.getLong("timestamp"))
        assertEquals(1700000000000L, rsA.getLong("createdAt"))
        assertEquals(1700000000000L, rsA.getLong("updatedAt"))
        assertEquals(200.0, rsA.getDouble("total"), 0.001)
        assertEquals("BRL", rsA.getString("currency"))
        assertEquals("MONEY", rsA.getString("paymentMethod"))
        assertEquals("op1", rsA.getString("operatorId"))
        assertEquals("João", rsA.getString("operatorName"))
        assertEquals("sess_1", rsA.getString("sessionId"))
        assertEquals(1, rsA.getInt("syncedToApi"))
        assertEquals(LocalSaleEntity.STATUS_SYNCED, rsA.getString("syncStatus"))
        assertEquals(0, rsA.getInt("idempotencyKeyUsed")) // Conservadoramente 0 (false) para legados
        rsA.close()

        val stmtB = connection.createStatement()
        val rsB = stmtB.executeQuery("SELECT * FROM local_sales WHERE localId = 'sale_b_pending'")
        assertTrue("Venda B legada pendente deve existir após migração v3 -> v9", rsB.next())
        assertEquals("sale_b_pending", rsB.getString("localId"))
        assertNull(rsB.getString("apiId"))
        assertEquals(1700000050000L, rsB.getLong("timestamp"))
        assertEquals(1700000050000L, rsB.getLong("createdAt"))
        assertEquals(1700000050000L, rsB.getLong("updatedAt"))
        assertEquals(500.0, rsB.getDouble("total"), 0.001)
        assertEquals("PYG", rsB.getString("currency"))
        assertEquals("CARD", rsB.getString("paymentMethod"))
        assertEquals("op2", rsB.getString("operatorId"))
        assertEquals("Maria", rsB.getString("operatorName"))
        assertEquals("sess_2", rsB.getString("sessionId"))
        assertEquals(0, rsB.getInt("syncedToApi"))
        assertEquals(LocalSaleEntity.STATUS_NEEDS_RECONCILIATION, rsB.getString("syncStatus"))
        assertEquals("{}", rsB.getString("payloadJson"))
        assertEquals(0, rsB.getInt("idempotencyKeyUsed")) // Conservadoramente 0 (false) para legados
        rsB.close()

        // 4. Auditoria: Validar estrutura de produtos
        val stmtProducts = connection.createStatement()
        val rsProducts = stmtProducts.executeQuery("PRAGMA table_info(products)")
        val productColumns = mutableListOf<String>()
        while (rsProducts.next()) {
            productColumns.add(rsProducts.getString("name"))
        }
        rsProducts.close()

        assertTrue(productColumns.contains("id"))
        assertTrue(productColumns.contains("name"))
        assertTrue(productColumns.contains("selling_price"))

        connection.close()
    }

    @Test
    fun testMigrationVersionsAndSqlStatements() {
        assertEquals(4, AppDatabase.MIGRATION_3_4.endVersion)
        assertEquals(3, AppDatabase.MIGRATION_3_4.startVersion)

        assertEquals(5, AppDatabase.MIGRATION_4_5.endVersion)
        assertEquals(4, AppDatabase.MIGRATION_4_5.startVersion)

        assertEquals(6, AppDatabase.MIGRATION_5_6.endVersion)
        assertEquals(5, AppDatabase.MIGRATION_5_6.startVersion)

        assertEquals(7, AppDatabase.MIGRATION_6_7.endVersion)
        assertEquals(6, AppDatabase.MIGRATION_6_7.startVersion)

        assertEquals(8, AppDatabase.MIGRATION_7_8.endVersion)
        assertEquals(7, AppDatabase.MIGRATION_7_8.startVersion)

        assertEquals(9, AppDatabase.MIGRATION_8_9.endVersion)
        assertEquals(8, AppDatabase.MIGRATION_8_9.startVersion)

        assertEquals(10, AppDatabase.MIGRATION_9_10.endVersion)
        assertEquals(9, AppDatabase.MIGRATION_9_10.startVersion)
    }

    @Test
    fun testRealSqliteMigrationFromVersion9To10CreatesComandaSnapshotsWithoutDataLoss() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val db = mock<SupportSQLiteDatabase>()

        whenever(db.execSQL(any())).thenAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            connection.createStatement().use { stmt ->
                stmt.execute(sql)
            }
            Unit
        }

        // 1. Setup version 9 schema
        db.execSQL("""
            CREATE TABLE `local_sales` (
                `localId` TEXT NOT NULL,
                `apiId` TEXT,
                `timestamp` INTEGER NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                `total` REAL NOT NULL,
                `currency` TEXT NOT NULL,
                `paymentMethod` TEXT NOT NULL,
                `operatorId` TEXT,
                `operatorName` TEXT,
                `sessionId` TEXT,
                `itemsJson` TEXT NOT NULL,
                `customerName` TEXT,
                `taxAmount` REAL NOT NULL,
                `serviceFeeAmount` REAL NOT NULL,
                `serviceFeeKind` TEXT,
                `convertedTotal` REAL NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `attemptCount` INTEGER NOT NULL,
                `lastError` TEXT,
                `lastAttemptAt` INTEGER,
                `syncStatus` TEXT NOT NULL,
                `syncedToApi` INTEGER NOT NULL,
                `idempotencyKeyUsed` INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(`localId`)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE `outbox_operations` (
                `id` TEXT NOT NULL,
                `operationType` TEXT NOT NULL,
                `targetGroupKey` TEXT NOT NULL,
                `payloadJson` TEXT NOT NULL,
                `createdAt` INTEGER NOT NULL,
                `idempotencyKey` TEXT NOT NULL,
                `serverSeq` INTEGER,
                `attemptCount` INTEGER NOT NULL,
                `lastAttemptAt` INTEGER,
                `nextRetryAt` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `lastError` TEXT,
                `messageKey` TEXT,
                `isRetriable` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE `payment_attempts` (
                `reference` TEXT NOT NULL,
                `idempotencyKey` TEXT NOT NULL,
                `nonce` TEXT NOT NULL,
                `amount` INTEGER NOT NULL,
                `currency` TEXT NOT NULL,
                `status` TEXT NOT NULL,
                `startedAt` INTEGER NOT NULL,
                `completedAt` INTEGER,
                `paymentMethod` TEXT,
                `tableNumber` INTEGER,
                `orderId` TEXT,
                `description` TEXT,
                `rawCallbackUri` TEXT,
                `paymentAppPaymentId` TEXT,
                `statusMessage` TEXT,
                PRIMARY KEY(`reference`)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE `products` (
                `id` TEXT NOT NULL,
                `name` TEXT,
                `sku` TEXT,
                `barcode` TEXT,
                `category` TEXT,
                `selling_price` REAL,
                `stock` INTEGER,
                `image_url` TEXT,
                `price_currency` TEXT,
                `group_id` TEXT,
                `group_name` TEXT,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE `tables` (
                `id` TEXT NOT NULL,
                `number` INTEGER NOT NULL,
                `status` TEXT NOT NULL,
                `sectorName` TEXT NOT NULL,
                `sectorId` TEXT NOT NULL,
                `customerName` TEXT,
                `comandaId` TEXT,
                `peopleCount` INTEGER NOT NULL,
                `totalBalance` REAL NOT NULL,
                `paidAmount` REAL NOT NULL,
                `pendingBalance` REAL NOT NULL,
                `itemsJson` TEXT NOT NULL,
                `updatedAt` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())

        db.execSQL("""
            CREATE TABLE `tax_rates` (
                `id` TEXT NOT NULL,
                `name` TEXT NOT NULL,
                `rate` REAL NOT NULL,
                `is_active` INTEGER NOT NULL,
                PRIMARY KEY(`id`)
            )
        """.trimIndent())

        // Insert representative data into v9 tables
        db.execSQL("INSERT INTO local_sales (localId, timestamp, createdAt, updatedAt, total, currency, paymentMethod, itemsJson, taxAmount, serviceFeeAmount, convertedTotal, payloadJson, attemptCount, syncStatus, syncedToApi, idempotencyKeyUsed) VALUES ('s1', 100, 100, 100, 50.0, 'BRL', 'MONEY', '[]', 0.0, 0.0, 0.0, '{}', 0, 'PENDING', 0, 1)")
        db.execSQL("INSERT INTO outbox_operations (id, operationType, targetGroupKey, payloadJson, createdAt, idempotencyKey, attemptCount, nextRetryAt, status, isRetriable) VALUES ('op1', 'COMANDA_CHECKOUT_COMMIT', 'g1', '{}', 100, 'op1', 0, 0, 'PENDING', 1)")
        db.execSQL("INSERT INTO payment_attempts (reference, idempotencyKey, nonce, amount, currency, status, startedAt) VALUES ('ref1', 'ref1', 'n1', 5000, 'BRL', 'APPROVED', 100)")
        db.execSQL("INSERT INTO products (id, name, selling_price) VALUES ('p1', 'Burger', 25.0)")
        db.execSQL("INSERT INTO tables (id, number, status, sectorName, sectorId, peopleCount, totalBalance, paidAmount, pendingBalance, itemsJson, updatedAt) VALUES ('t1', 1, 'OCCUPIED', 'Salão', 'sec1', 2, 50.0, 0.0, 50.0, '[]', 100)")
        db.execSQL("INSERT INTO tax_rates (id, name, rate, is_active) VALUES ('tx1', 'IVA', 10.0, 1)")

        // 2. Execute Migration 9 -> 10
        AppDatabase.MIGRATION_9_10.migrate(db)

        // 3. Assert all existing data is preserved
        val stmtCheck = connection.createStatement()
        val rsSales = stmtCheck.executeQuery("SELECT COUNT(*) FROM local_sales")
        assertTrue(rsSales.next())
        assertEquals(1, rsSales.getInt(1))
        rsSales.close()

        val rsOutbox = stmtCheck.executeQuery("SELECT COUNT(*) FROM outbox_operations")
        assertTrue(rsOutbox.next())
        assertEquals(1, rsOutbox.getInt(1))
        rsOutbox.close()

        val rsPayment = stmtCheck.executeQuery("SELECT COUNT(*) FROM payment_attempts")
        assertTrue(rsPayment.next())
        assertEquals(1, rsPayment.getInt(1))
        rsPayment.close()

        val rsProducts = stmtCheck.executeQuery("SELECT COUNT(*) FROM products")
        assertTrue(rsProducts.next())
        assertEquals(1, rsProducts.getInt(1))
        rsProducts.close()

        val rsTables = stmtCheck.executeQuery("SELECT COUNT(*) FROM tables")
        assertTrue(rsTables.next())
        assertEquals(1, rsTables.getInt(1))
        rsTables.close()

        val rsTaxes = stmtCheck.executeQuery("SELECT COUNT(*) FROM tax_rates")
        assertTrue(rsTaxes.next())
        assertEquals(1, rsTaxes.getInt(1))
        rsTaxes.close()

        // 4. Assert comanda_snapshots exists and is empty
        val rsSnapshots = stmtCheck.executeQuery("SELECT COUNT(*) FROM comanda_snapshots")
        assertTrue(rsSnapshots.next())
        assertEquals(0, rsSnapshots.getInt(1))
        rsSnapshots.close()

        // 5. Test inserting into comanda_snapshots
        db.execSQL("""
            INSERT INTO comanda_snapshots (
                localComandaId, serverComandaId, tenantId, tableId, tableNumber,
                customerIdentifier, baseCurrency, baseMinorUnitDigits, serverStatus,
                localStatus, syncStatus, serverRevision, localRevision,
                totalBaseMinor, paidBaseMinor, balanceBaseMinor,
                itemsJson, paymentsJson, requiresReconciliation, reconciliationReason,
                serverUpdatedAt, cachedAt
            ) VALUES (
                'loc-1', 'srv-1', 'ten-1', 't1', 1,
                'Cliente A', 'BRL', 2, 'ABERTA',
                'OPEN', 'SYNCED', NULL, 0,
                5000, 0, 5000,
                '[]', '[]', 0, NULL,
                NULL, 1700000000000
            )
        """.trimIndent())

        val rsRead = stmtCheck.executeQuery("SELECT * FROM comanda_snapshots WHERE localComandaId = 'loc-1'")
        assertTrue(rsRead.next())
        assertEquals("srv-1", rsRead.getString("serverComandaId"))
        assertEquals("ten-1", rsRead.getString("tenantId"))
        assertEquals("BRL", rsRead.getString("baseCurrency"))
        assertEquals(2, rsRead.getInt("baseMinorUnitDigits"))
        assertEquals(5000L, rsRead.getLong("totalBaseMinor"))
        assertEquals(0L, rsRead.getLong("paidBaseMinor"))
        assertEquals(5000L, rsRead.getLong("balanceBaseMinor"))
        assertEquals("OPEN", rsRead.getString("localStatus"))
        assertEquals("SYNCED", rsRead.getString("syncStatus"))
        assertEquals(0, rsRead.getInt("requiresReconciliation"))
        rsRead.close()

        stmtCheck.close()
        connection.close()
    }
}
