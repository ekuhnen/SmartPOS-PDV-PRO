package com.plugpdv.pdv.database

import androidx.sqlite.db.SupportSQLiteDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.sql.DriverManager

class PaymentProviderMigrationTest {

    @Test
    fun migration12To13BackfillsLegacyAttemptsAndAllowsUnresolvedPreparedProvider() {
        val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
        val db = mock<SupportSQLiteDatabase>()

        whenever(db.execSQL(any())).thenAnswer { invocation ->
            val sql = invocation.getArgument<String>(0)
            connection.createStatement().use { it.execute(sql) }
            Unit
        }

        db.execSQL(
            """
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
            """.trimIndent()
        )
        db.execSQL("CREATE UNIQUE INDEX `index_payment_attempts_idempotencyKey` ON `payment_attempts` (`idempotencyKey`)")
        db.execSQL("CREATE INDEX `index_payment_attempts_tableNumber` ON `payment_attempts` (`tableNumber`)")
        db.execSQL("CREATE INDEX `index_payment_attempts_status` ON `payment_attempts` (`status`)")
        db.execSQL("CREATE INDEX `index_payment_attempts_startedAt` ON `payment_attempts` (`startedAt`)")
        db.execSQL(
            "INSERT INTO payment_attempts (reference,idempotencyKey,nonce,amount,currency,status,startedAt) " +
                "VALUES ('legacy-pay','legacy-key','nonce',1234,'BRL','APPROVED',1700000000000)"
        )

        AppDatabase.MIGRATION_12_13.migrate(db)

        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT provider FROM payment_attempts WHERE reference='legacy-pay'").use { rs ->
                assertTrue(rs.next())
                assertEquals("PLUGPAY", rs.getString("provider"))
            }

            statement.executeQuery("PRAGMA index_list(payment_attempts)").use { rs ->
                val indexes = mutableSetOf<String>()
                while (rs.next()) indexes += rs.getString("name")
                assertTrue(indexes.contains("index_payment_attempts_provider"))
            }

            statement.execute(
                "INSERT INTO payment_attempts (reference,idempotencyKey,nonce,amount,currency,status,startedAt) " +
                    "VALUES ('new-prepared','new-key','nonce2',5000,'BRL','PREPARED',1700000001000)"
            )
            statement.executeQuery("SELECT provider FROM payment_attempts WHERE reference='new-prepared'").use { rs ->
                assertTrue(rs.next())
                assertNull(rs.getString("provider"))
            }
        }

        connection.close()
    }

    @Test
    fun migrationVersionIsExactly12To13() {
        assertEquals(12, AppDatabase.MIGRATION_12_13.startVersion)
        assertEquals(13, AppDatabase.MIGRATION_12_13.endVersion)
    }
}
