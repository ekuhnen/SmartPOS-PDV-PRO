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
    }
}
