package com.plugpdv.pdv.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.plugpdv.pdv.models.Product

@Database(
    entities = [
        TaxEntity::class,
        Product::class,
        LocalSaleEntity::class,
        OutboxOperationEntity::class,
        PaymentAttemptEntity::class,
        TableEntity::class,
        ComandaSnapshotEntity::class
    ],
    version = 10,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taxDao(): TaxDao
    abstract fun catalogDao(): CatalogDao
    abstract fun localSaleDao(): LocalSaleDao
    abstract fun outboxDao(): OutboxDao
    abstract fun paymentAttemptDao(): PaymentAttemptDao
    abstract fun tableDao(): TableDao
    abstract fun comandaSnapshotDao(): ComandaSnapshotDao

    suspend fun clearRebuildableCaches() {
        taxDao().deleteAll()
        catalogDao().deleteAll()
        tableDao().deleteAll()
    }

    suspend fun hasUnresolvedDurableWork(context: Context): Boolean {
        if (localSaleDao().getUnresolvedCount() > 0) return true
        if (paymentAttemptDao().getUnresolvedCount() > 0) return true
        if (outboxDao().getUnresolvedCount() > 0) return true
        if (com.plugpdv.pdv.utils.DirectPaymentReconciliationStore.isReconciliationRequired(context)) return true
        return false
    }

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `outbox_operations` (
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
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_operations_targetGroupKey` ON `outbox_operations` (`targetGroupKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_operations_status` ON `outbox_operations` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_operations_createdAt` ON `outbox_operations` (`createdAt`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `payment_attempts` (
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
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_payment_attempts_idempotencyKey` ON `payment_attempts` (`idempotencyKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_attempts_tableNumber` ON `payment_attempts` (`tableNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_attempts_status` ON `payment_attempts` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_attempts_startedAt` ON `payment_attempts` (`startedAt`)")
            }
        }

        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `outbox_operations` (
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
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_operations_targetGroupKey` ON `outbox_operations` (`targetGroupKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_operations_status` ON `outbox_operations` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_operations_createdAt` ON `outbox_operations` (`createdAt`)")

                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `payment_attempts` (
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
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_payment_attempts_idempotencyKey` ON `payment_attempts` (`idempotencyKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_attempts_tableNumber` ON `payment_attempts` (`tableNumber`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_attempts_status` ON `payment_attempts` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_payment_attempts_startedAt` ON `payment_attempts` (`startedAt`)")
            }
        }

        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Reconstruir outbox_operations para bater 100% com o schema sem residual de DEFAULT
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `outbox_operations_new` (
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
                    INSERT INTO `outbox_operations_new` (
                        id, operationType, targetGroupKey, payloadJson, createdAt,
                        idempotencyKey, serverSeq, attemptCount, lastAttemptAt,
                        nextRetryAt, status, lastError, messageKey, isRetriable
                    )
                    SELECT 
                        id, operationType, targetGroupKey, payloadJson, createdAt,
                        id AS idempotencyKey, NULL AS serverSeq, attemptCount, lastAttemptAt,
                        nextRetryAt, status, lastError, NULL AS messageKey, isRetriable
                    FROM `outbox_operations`
                """.trimIndent())

                db.execSQL("DROP TABLE `outbox_operations`")
                db.execSQL("ALTER TABLE `outbox_operations_new` RENAME TO `outbox_operations`")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_operations_targetGroupKey` ON `outbox_operations` (`targetGroupKey`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_operations_status` ON `outbox_operations` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_outbox_operations_createdAt` ON `outbox_operations` (`createdAt`)")
            }
        }

        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `tables` (
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
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tables_sectorId_number` ON `tables` (`sectorId`, `number`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tables_status` ON `tables` (`status`)")
            }
        }

        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. Criar a nova tabela local_sales com a DDL EXATA derivada do 8.json
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `local_sales_new` (
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
                        PRIMARY KEY(`localId`)
                    )
                """.trimIndent())

                // 2. Copiar 100% das vendas legadas sem perda de dados financeiros
                db.execSQL("""
                    INSERT INTO `local_sales_new` (
                        localId, apiId, timestamp, createdAt, updatedAt, total, currency, paymentMethod,
                        operatorId, operatorName, sessionId, itemsJson, customerName, taxAmount,
                        serviceFeeAmount, serviceFeeKind, convertedTotal, payloadJson, attemptCount,
                        lastError, lastAttemptAt, syncStatus, syncedToApi
                    )
                    SELECT 
                        localId,
                        apiId,
                        timestamp,
                        timestamp AS createdAt,
                        timestamp AS updatedAt,
                        total,
                        currency,
                        paymentMethod,
                        operatorId,
                        operatorName,
                        sessionId,
                        itemsJson,
                        'Consumidor Final' AS customerName,
                        0.0 AS taxAmount,
                        0.0 AS serviceFeeAmount,
                        NULL AS serviceFeeKind,
                        0.0 AS convertedTotal,
                        '{}' AS payloadJson,
                        0 AS attemptCount,
                        NULL AS lastError,
                        NULL AS lastAttemptAt,
                        CASE WHEN syncedToApi = 1 THEN 'SYNCED' ELSE 'NEEDS_RECONCILIATION' END AS syncStatus,
                        syncedToApi
                    FROM `local_sales`
                """.trimIndent())

                // 3. Remover a tabela antiga e renomear
                db.execSQL("DROP TABLE `local_sales`")
                db.execSQL("ALTER TABLE `local_sales_new` RENAME TO `local_sales`")

                // 4. Reconstruir a tabela de produtos (cache de catálogo reconstruível)
                db.execSQL("DROP TABLE IF EXISTS `products`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `products` (
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

                // 5. Reconstruir a tabela de mesas (cache reconstruível)
                db.execSQL("DROP TABLE IF EXISTS `tables`")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `tables` (
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
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_tables_sectorId_number` ON `tables` (`sectorId`, `number`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_tables_status` ON `tables` (`status`)")
            }
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `local_sales` ADD COLUMN `idempotencyKeyUsed` INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `comanda_snapshots` (
                        `localComandaId` TEXT NOT NULL,
                        `serverComandaId` TEXT,
                        `tenantId` TEXT NOT NULL,
                        `tableId` TEXT,
                        `tableNumber` INTEGER,
                        `customerIdentifier` TEXT,
                        `baseCurrency` TEXT,
                        `baseMinorUnitDigits` INTEGER,
                        `serverStatus` TEXT,
                        `localStatus` TEXT NOT NULL,
                        `syncStatus` TEXT NOT NULL,
                        `serverRevision` INTEGER,
                        `localRevision` INTEGER NOT NULL,
                        `totalBaseMinor` INTEGER,
                        `paidBaseMinor` INTEGER,
                        `balanceBaseMinor` INTEGER,
                        `itemsJson` TEXT NOT NULL,
                        `paymentsJson` TEXT NOT NULL,
                        `requiresReconciliation` INTEGER NOT NULL,
                        `reconciliationReason` TEXT,
                        `serverUpdatedAt` INTEGER,
                        `cachedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`localComandaId`)
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_comanda_snapshots_tenantId_serverComandaId` ON `comanda_snapshots` (`tenantId`, `serverComandaId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_comanda_snapshots_tableId` ON `comanda_snapshots` (`tableId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_comanda_snapshots_localStatus` ON `comanda_snapshots` (`localStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_comanda_snapshots_syncStatus` ON `comanda_snapshots` (`syncStatus`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_comanda_snapshots_requiresReconciliation` ON `comanda_snapshots` (`requiresReconciliation`)")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smartpos_database"
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
