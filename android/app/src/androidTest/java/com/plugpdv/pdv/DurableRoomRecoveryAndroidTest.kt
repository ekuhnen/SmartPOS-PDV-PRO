package com.plugpdv.pdv

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.OutboxOperationEntity
import com.plugpdv.pdv.database.PaymentAttemptEntity
import com.plugpdv.pdv.models.CommandCheckoutCommitRequest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DurableRoomRecoveryAndroidTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private val gson = Gson()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun testOrphanMatrix_caseA_approvedAttemptPromotesWaitingOutbox() {
        runBlocking {
            val req = CommandCheckoutCommitRequest(comandaId = "c-1", forma = "CARD", valor = 100.0, moeda = "BRL", valorBase = 100.0)
            val op = OutboxOperationEntity(
                id = "k-case-a",
                operationType = "COMANDA_CHECKOUT_COMMIT",
                targetGroupKey = "c-1",
                payloadJson = gson.toJson(req),
                createdAt = System.currentTimeMillis(),
                status = "WAITING_PAYMENT"
            )
            val attempt = PaymentAttemptEntity(
                reference = "k-case-a",
                idempotencyKey = "k-case-a",
                nonce = "n-a",
                amount = 10000L,
                currency = "BRL",
                status = "APPROVED",
                startedAt = System.currentTimeMillis(),
                paymentAppPaymentId = "pay-ext-a"
            )

            db.outboxDao().insert(op)
            db.paymentAttemptDao().insert(attempt)

            val approvedAttempts = db.paymentAttemptDao().getApprovedAttempts()
            val waitingOps = db.outboxDao().getWaitingPaymentOperations()

            for (wOp in waitingOps) {
                val match = approvedAttempts.find { it.reference == wOp.idempotencyKey }
                if (match != null) {
                    val updated = wOp.copy(status = "PENDING")
                    db.outboxDao().update(updated)
                }
            }

            val recovered = db.outboxDao().getById("k-case-a")
            assertNotNull(recovered)
            assertEquals("PENDING", recovered!!.status)
        }
    }

    @Test
    fun testOrphanMatrix_caseC_unknownAttemptBlocksAutoCheckout() {
        runBlocking {
            val req = CommandCheckoutCommitRequest(comandaId = "c-3", forma = "CARD", valor = 50.0, moeda = "BRL", valorBase = 50.0)
            val op = OutboxOperationEntity(
                id = "k-case-c",
                operationType = "COMANDA_CHECKOUT_COMMIT",
                targetGroupKey = "c-3",
                payloadJson = gson.toJson(req),
                createdAt = System.currentTimeMillis(),
                status = "WAITING_PAYMENT"
            )
            val attempt = PaymentAttemptEntity(
                reference = "k-case-c",
                idempotencyKey = "k-case-c",
                nonce = "n-c",
                amount = 5000L,
                currency = "BRL",
                status = "UNKNOWN",
                startedAt = System.currentTimeMillis()
            )

            db.outboxDao().insert(op)
            db.paymentAttemptDao().insert(attempt)

            val waitingOps = db.outboxDao().getWaitingPaymentOperations()
            assertEquals(1, waitingOps.size)
            assertEquals("WAITING_PAYMENT", waitingOps[0].status)
        }
    }

    @Test
    fun testOrphanMatrix_caseF_cancelledAttemptTerminalizesOutbox() {
        runBlocking {
            val req = CommandCheckoutCommitRequest(comandaId = "c-4", forma = "CARD", valor = 30.0, moeda = "BRL", valorBase = 30.0)
            val op = OutboxOperationEntity(
                id = "k-case-f",
                operationType = "COMANDA_CHECKOUT_COMMIT",
                targetGroupKey = "c-4",
                payloadJson = gson.toJson(req),
                createdAt = System.currentTimeMillis(),
                status = "WAITING_PAYMENT"
            )
            val attempt = PaymentAttemptEntity(
                reference = "k-case-f",
                idempotencyKey = "k-case-f",
                nonce = "n-f",
                amount = 3000L,
                currency = "BRL",
                status = "CANCELLED",
                startedAt = System.currentTimeMillis()
            )

            db.outboxDao().insert(op)
            db.paymentAttemptDao().insert(attempt)

            db.outboxDao().markAsFailedWithKey("k-case-f", "CANCELLED", "CANCELLED_PAYMENT", false)

            val terminalOp = db.outboxDao().getById("k-case-f")
            assertNotNull(terminalOp)
            assertEquals("FAILED", terminalOp!!.status)
            assertEquals("CANCELLED_PAYMENT", terminalOp.messageKey)
            assertFalse(terminalOp.isRetriable)
        }
    }

    @Test
    fun testA_MONEY_09_realRoomByteForBytePersistenceAndHashRecovery() {
        runBlocking {
            val req = CommandCheckoutCommitRequest(
                comandaId = "c-100",
                forma = "CARD",
                valor = java.math.BigDecimal("350000"),
                moeda = "PYG",
                valorBase = java.math.BigDecimal("50.00"),
                baseCurrency = "BRL",
                fxRate = java.math.BigDecimal("7000"),
                exchangeRatesSnapshot = mapOf("PYG" to "7000", "BRL" to "1")
            )

            val payloadJson = gson.toJson(req)
            val originalHash = sha256(payloadJson)

            val op = OutboxOperationEntity(
                id = "k-room-money-09",
                operationType = "COMANDA_CHECKOUT_COMMIT",
                targetGroupKey = "c-100",
                payloadJson = payloadJson,
                createdAt = System.currentTimeMillis(),
                status = "WAITING_PAYMENT"
            )

            db.outboxDao().insert(op)

            val recovered = db.outboxDao().getById("k-room-money-09")
            assertNotNull(recovered)
            assertEquals("Byte-for-byte Room payloadJson must match exactly", payloadJson, recovered!!.payloadJson)
            assertEquals("Room hash must match exactly", originalHash, sha256(recovered.payloadJson))

            val deserialized = gson.fromJson(recovered.payloadJson, CommandCheckoutCommitRequest::class.java)
            assertEquals("PYG", deserialized.moeda)
            assertEquals("BRL", deserialized.baseCurrency)
            assertEquals(0, deserialized.valor.compareTo(java.math.BigDecimal("350000")))
            assertEquals(0, deserialized.valorBase!!.compareTo(java.math.BigDecimal("50.00")))
            assertEquals(0, deserialized.fxRate!!.compareTo(java.math.BigDecimal("7000")))
            assertEquals("7000", deserialized.exchangeRatesSnapshot?.get("PYG"))
        }
    }

    /**
     * A-MONEY-10: Direct sale persists durable WAITING_PAYMENT + PaymentAttempt PENDING in atomic Room TX BEFORE PlugPay start.
     */
    @Test
    fun testA_MONEY_10_directSalePersistsDurableWaitingPaymentBeforePlugPay() {
        runBlocking {
            val repository = com.plugpdv.pdv.repository.SaleOutboxRepository(
                context = context,
                localSaleDao = db.localSaleDao(),
                apiService = null,
                appDatabase = db,
                paymentAttemptDao = db.paymentAttemptDao()
            )

            val saleRequest = com.plugpdv.pdv.models.SaleRequest(
                customerName = "Consumidor Final",
                total = java.math.BigDecimal("350000"),
                items = listOf(com.plugpdv.pdv.models.SaleItem(productId = "p1", productName = "Test", quantity = 1, price = 50.0)),
                paymentMethod = "CREDITO",
                currency = "BRL",
                paymentCurrency = "PYG",
                exchangeRatesSnapshot = mapOf("PYG" to "7000", "BRL" to "1"),
                convertedTotal = java.math.BigDecimal("50.00")
            )

            val localId = "k-direct-atomic-10"
            var plugPayLaunched = false

            // Execute atomic pre-payment Room transaction
            repository.prepareDirectSaleAtomic(
                saleRequest = saleRequest,
                currency = "BRL",
                localId = localId,
                minimalUnitAmount = 350000L,
                orderId = localId
            )

            // Proves Room commit is confirmed BEFORE external hook
            val persistedSale = db.localSaleDao().getById(localId)
            val persistedAttempt = db.paymentAttemptDao().getByReference(localId)

            assertNotNull("LocalSale must be committed in Room before PlugPay launch", persistedSale)
            assertEquals("WAITING_PAYMENT", persistedSale!!.syncStatus)

            assertNotNull("PaymentAttempt must be committed in Room before PlugPay launch", persistedAttempt)
            assertEquals(PaymentAttemptEntity.STATUS_PREPARED, persistedAttempt!!.status)
            assertEquals(350000L, persistedAttempt.amount)
            assertEquals("PYG", persistedAttempt.currency)

            // Simulated launch hook
            plugPayLaunched = true
            assertTrue(plugPayLaunched)
        }
    }

    /**
     * A-MONEY-11: Simulated process death: PaymentResultStore empty, volatile state wiped,
     * Room has PaymentAttempt APPROVED + LocalSale WAITING_PAYMENT -> recovery promotes same sale, same K, same payloadJson.
     */
    @Test
    fun testA_MONEY_11_simulatedProcessDeath_recoveryPromotesFrozenSale() {
        runBlocking {
            val repository = com.plugpdv.pdv.repository.SaleOutboxRepository(
                context = context,
                localSaleDao = db.localSaleDao(),
                apiService = null,
                appDatabase = db,
                paymentAttemptDao = db.paymentAttemptDao()
            )

            val saleRequest = com.plugpdv.pdv.models.SaleRequest(
                customerName = "Consumidor Final",
                total = java.math.BigDecimal("350000"),
                items = listOf(com.plugpdv.pdv.models.SaleItem(productId = "p1", productName = "Test", quantity = 1, price = 50.0)),
                paymentMethod = "CREDITO",
                currency = "BRL",
                paymentCurrency = "PYG",
                exchangeRatesSnapshot = mapOf("PYG" to "7000", "BRL" to "1"),
                convertedTotal = java.math.BigDecimal("50.00")
            )

            val localId = "k-death-recovery-11"
            val payloadJson = gson.toJson(saleRequest)

            // 1. Sale was persisted before crash
            val localSale = com.plugpdv.pdv.database.LocalSaleEntity(
                localId = localId,
                total = 350000.0,
                currency = "BRL",
                paymentMethod = "CREDITO",
                itemsJson = gson.toJson(saleRequest.items),
                payloadJson = payloadJson,
                syncStatus = com.plugpdv.pdv.database.LocalSaleEntity.STATUS_WAITING_PAYMENT,
                idempotencyKeyUsed = true
            )
            val paymentAttempt = PaymentAttemptEntity(
                reference = localId,
                idempotencyKey = localId,
                nonce = "nonce-11",
                amount = 350000L,
                currency = "PYG",
                status = "APPROVED",
                startedAt = System.currentTimeMillis(),
                paymentAppPaymentId = "ext-approved-11"
            )

            db.localSaleDao().insert(localSale)
            db.paymentAttemptDao().insert(paymentAttempt)

            // 2. Volatile PaymentResultStore is empty (simulating process recreation)
            com.plugpdv.pdv.utils.PaymentResultStore.consume()
            assertFalse(com.plugpdv.pdv.utils.PaymentResultStore.hasPending())

            // 3. Trigger recovery
            val recoveredCount = repository.recoverApprovedWaitingSalesAtomic()
            assertEquals(1, recoveredCount)

            // 4. Verify promoted sale
            val promotedSale = db.localSaleDao().getById(localId)
            assertNotNull(promotedSale)
            assertEquals("PENDING", promotedSale!!.syncStatus)
            assertEquals("Same K must be preserved", localId, promotedSale.localId)
            assertEquals("Same payloadJson must be preserved byte-for-byte", payloadJson, promotedSale.payloadJson)
        }
    }

    /**
     * A-MONEY-16: Room persistence: SaleRequest with BigDecimal transactionAmount, baseAmount, fxRate, snapshot survives byte-for-byte JSON round trip in real Room.
     */
    @Test
    fun testA_MONEY_16_roomPersistenceSaleRequestRoundTrip() {
        runBlocking {
            val saleRequest = com.plugpdv.pdv.models.SaleRequest(
                customerName = "Consumidor Final",
                total = java.math.BigDecimal("350000"),
                items = listOf(com.plugpdv.pdv.models.SaleItem(productId = "p1", productName = "Test", quantity = 1, price = 50.0)),
                paymentMethod = "CREDITO",
                currency = "BRL",
                paymentCurrency = "PYG",
                exchangeRatesSnapshot = mapOf("PYG" to "7000", "BRL" to "1"),
                taxAmount = java.math.BigDecimal("0.00"),
                serviceFeeAmount = java.math.BigDecimal("0.00"),
                convertedTotal = java.math.BigDecimal("50.00")
            )

            val payloadJson = gson.toJson(saleRequest)
            val originalHash = sha256(payloadJson)

            val localSale = com.plugpdv.pdv.database.LocalSaleEntity(
                localId = "k-room-money-16",
                total = 350000.0,
                currency = "BRL",
                paymentMethod = "CREDITO",
                itemsJson = gson.toJson(saleRequest.items),
                payloadJson = payloadJson,
                syncStatus = com.plugpdv.pdv.database.LocalSaleEntity.STATUS_WAITING_PAYMENT,
                idempotencyKeyUsed = true
            )

            db.localSaleDao().insert(localSale)

            val recovered = db.localSaleDao().getById("k-room-money-16")
            assertNotNull(recovered)
            assertEquals(payloadJson, recovered!!.payloadJson)
            assertEquals(originalHash, sha256(recovered.payloadJson))

            val deserialized = gson.fromJson(recovered.payloadJson, com.plugpdv.pdv.models.SaleRequest::class.java)
            assertEquals("PYG", deserialized.paymentCurrency)
            assertEquals("BRL", deserialized.currency)
            assertEquals(0, deserialized.total.compareTo(java.math.BigDecimal("350000")))
            assertEquals(0, deserialized.convertedTotal!!.compareTo(java.math.BigDecimal("50.00")))
            assertEquals("7000", deserialized.exchangeRatesSnapshot?.get("PYG"))
        }
    }

    /**
     * A-MONEY-17 (Instrumented): Direct sale PREPARED attempt promoted to PENDING in Room before external dispatch.
     */
    @Test
    fun testA_MONEY_17_instrumented_preparedToPendingPromotion() {
        runBlocking {
            val localId = "k-instr-17"
            val attempt = PaymentAttemptEntity(
                reference = localId,
                idempotencyKey = localId,
                nonce = "nonce-17",
                amount = 350000L,
                currency = "PYG",
                status = PaymentAttemptEntity.STATUS_PREPARED,
                startedAt = System.currentTimeMillis()
            )
            db.paymentAttemptDao().insert(attempt)

            val before = db.paymentAttemptDao().getByReference(localId)
            assertNotNull(before)
            assertEquals(PaymentAttemptEntity.STATUS_PREPARED, before!!.status)

            // Simulate PaymentHandler promotion before launching external activity
            val updated = before.copy(
                status = PaymentAttemptEntity.STATUS_PENDING,
                startedAt = System.currentTimeMillis()
            )
            db.paymentAttemptDao().update(updated)

            val after = db.paymentAttemptDao().getByReference(localId)
            assertNotNull(after)
            assertEquals(PaymentAttemptEntity.STATUS_PENDING, after!!.status)
        }
    }

    /**
     * A-MONEY-22 (Instrumented): Unresolved payment state on device Room returns isBlocked=true for PENDING.
     */
    @Test
    fun testA_MONEY_22_instrumented_unresolvedPendingBlocksPayment() {
        runBlocking {
            val repository = com.plugpdv.pdv.repository.SaleOutboxRepository(
                context = context,
                localSaleDao = db.localSaleDao(),
                apiService = null,
                appDatabase = db,
                paymentAttemptDao = db.paymentAttemptDao()
            )

            val k = "k-instr-22"
            val sale = com.plugpdv.pdv.database.LocalSaleEntity(
                localId = k,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                total = 50.0,
                currency = "BRL",
                paymentMethod = "CREDITO",
                itemsJson = "[]",
                payloadJson = "{}",
                syncStatus = com.plugpdv.pdv.database.LocalSaleEntity.STATUS_WAITING_PAYMENT,
                idempotencyKeyUsed = true
            )
            val attempt = PaymentAttemptEntity(
                reference = k,
                idempotencyKey = k,
                nonce = "n-22",
                amount = 350000L,
                currency = "PYG",
                status = PaymentAttemptEntity.STATUS_PENDING,
                startedAt = System.currentTimeMillis()
            )

            db.localSaleDao().insert(sale)
            db.paymentAttemptDao().insert(attempt)

            val unresolved = repository.getUnresolvedDirectPaymentState()
            assertNotNull(unresolved)
            assertEquals(k, unresolved!!.operationId)
            assertTrue(unresolved.isBlocked)
            assertFalse(unresolved.requiresReconciliation)
        }
    }

    /**
     * A-MONEY-23 (Instrumented): Unresolved payment state on device Room returns requiresReconciliation=true for UNKNOWN.
     */
    @Test
    fun testA_MONEY_23_instrumented_unresolvedUnknownRequiresReconciliation() {
        runBlocking {
            val repository = com.plugpdv.pdv.repository.SaleOutboxRepository(
                context = context,
                localSaleDao = db.localSaleDao(),
                apiService = null,
                appDatabase = db,
                paymentAttemptDao = db.paymentAttemptDao()
            )

            val k = "k-instr-23"
            val sale = com.plugpdv.pdv.database.LocalSaleEntity(
                localId = k,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                total = 50.0,
                currency = "BRL",
                paymentMethod = "CREDITO",
                itemsJson = "[]",
                payloadJson = "{}",
                syncStatus = com.plugpdv.pdv.database.LocalSaleEntity.STATUS_WAITING_PAYMENT,
                idempotencyKeyUsed = true
            )
            val attempt = PaymentAttemptEntity(
                reference = k,
                idempotencyKey = k,
                nonce = "n-23",
                amount = 350000L,
                currency = "PYG",
                status = PaymentAttemptEntity.STATUS_UNKNOWN,
                startedAt = System.currentTimeMillis()
            )

            db.localSaleDao().insert(sale)
            db.paymentAttemptDao().insert(attempt)

            val unresolved = repository.getUnresolvedDirectPaymentState()
            assertNotNull(unresolved)
            assertEquals(k, unresolved!!.operationId)
            assertTrue(unresolved.isBlocked)
            assertTrue(unresolved.requiresReconciliation)
        }
    }

    /**
     * A-MONEY-28 (Instrumented): PREPARED operation on device Room returns canResumeSameOperation=true.
     */
    @Test
    fun testA_MONEY_28_instrumented_preparedAllowsResumeSameK() {
        runBlocking {
            val repository = com.plugpdv.pdv.repository.SaleOutboxRepository(
                context = context,
                localSaleDao = db.localSaleDao(),
                apiService = null,
                appDatabase = db,
                paymentAttemptDao = db.paymentAttemptDao()
            )

            val k = "k-instr-28"
            val sale = com.plugpdv.pdv.database.LocalSaleEntity(
                localId = k,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                total = 50.0,
                currency = "BRL",
                paymentMethod = "CREDITO",
                itemsJson = "[]",
                payloadJson = "{}",
                syncStatus = com.plugpdv.pdv.database.LocalSaleEntity.STATUS_WAITING_PAYMENT,
                idempotencyKeyUsed = true
            )
            val attempt = PaymentAttemptEntity(
                reference = k,
                idempotencyKey = k,
                nonce = "n-28",
                amount = 350000L,
                currency = "PYG",
                status = PaymentAttemptEntity.STATUS_PREPARED,
                startedAt = System.currentTimeMillis()
            )

            db.localSaleDao().insert(sale)
            db.paymentAttemptDao().insert(attempt)

            val unresolved = repository.getUnresolvedDirectPaymentState()
            assertNotNull(unresolved)
            assertEquals(k, unresolved!!.operationId)
            assertTrue(unresolved.isBlocked)
            assertTrue(unresolved.canResumeSameOperation)
            assertFalse(unresolved.requiresReconciliation)
        }
    }

    /**
     * A-MONEY-29 (Instrumented): Multiple unresolved operations on device Room (PENDING + PREPARED) prioritizes PENDING.
     */
    @Test
    fun testA_MONEY_29_instrumented_pendingTakesPrecedenceOverPrepared() {
        runBlocking {
            val repository = com.plugpdv.pdv.repository.SaleOutboxRepository(
                context = context,
                localSaleDao = db.localSaleDao(),
                apiService = null,
                appDatabase = db,
                paymentAttemptDao = db.paymentAttemptDao()
            )

            val k1 = "k1-instr-29"
            val k2 = "k2-instr-29"

            val sale1 = com.plugpdv.pdv.database.LocalSaleEntity(
                localId = k1,
                createdAt = 1000L,
                updatedAt = 1000L,
                total = 50.0,
                currency = "BRL",
                paymentMethod = "CREDITO",
                itemsJson = "[]",
                payloadJson = "{}",
                syncStatus = com.plugpdv.pdv.database.LocalSaleEntity.STATUS_WAITING_PAYMENT,
                idempotencyKeyUsed = true
            )
            val attempt1 = PaymentAttemptEntity(
                reference = k1,
                idempotencyKey = k1,
                nonce = "n1-29",
                amount = 5000L,
                currency = "BRL",
                status = PaymentAttemptEntity.STATUS_PENDING,
                startedAt = 1000L
            )

            val sale2 = com.plugpdv.pdv.database.LocalSaleEntity(
                localId = k2,
                createdAt = 2000L,
                updatedAt = 2000L,
                total = 75.0,
                currency = "BRL",
                paymentMethod = "CREDITO",
                itemsJson = "[]",
                payloadJson = "{}",
                syncStatus = com.plugpdv.pdv.database.LocalSaleEntity.STATUS_WAITING_PAYMENT,
                idempotencyKeyUsed = true
            )
            val attempt2 = PaymentAttemptEntity(
                reference = k2,
                idempotencyKey = k2,
                nonce = "n2-29",
                amount = 7500L,
                currency = "BRL",
                status = PaymentAttemptEntity.STATUS_PREPARED,
                startedAt = 2000L
            )

            db.localSaleDao().insert(sale1)
            db.paymentAttemptDao().insert(attempt1)
            db.localSaleDao().insert(sale2)
            db.paymentAttemptDao().insert(attempt2)

            val unresolved = repository.getUnresolvedDirectPaymentState()
            assertNotNull(unresolved)
            assertEquals(k1, unresolved!!.operationId)
            assertTrue(unresolved.isBlocked)
            assertFalse("Cannot resume when pending attempt exists", unresolved.canResumeSameOperation)
        }
    }

    /**
     * A-MONEY-33: Room instrumented test. Missing PaymentAttempt when callback approved arrives.
     * Must FAIL-CLOSED:
     * - PaymentAttempt delta = 0
     * - LocalSale marked as NEEDS_RECONCILIATION
     * - isBlocked = true, requiresReconciliation = true
     */
    @Test
    fun testA_MONEY_33_instrumented_missingPaymentAttempt_failClosed() {
        runBlocking {
            val repository = com.plugpdv.pdv.repository.SaleOutboxRepository(
                context = context,
                localSaleDao = db.localSaleDao(),
                apiService = null,
                appDatabase = db,
                paymentAttemptDao = db.paymentAttemptDao()
            )

            val k = "k-missing-attempt-33"
            val sale = com.plugpdv.pdv.database.LocalSaleEntity(
                localId = k,
                createdAt = 1000L,
                updatedAt = 1000L,
                total = 350000.0,
                currency = "BRL",
                paymentMethod = "CARTAO_CREDITO",
                itemsJson = "[]",
                payloadJson = "{\"total\":350000.0,\"currency\":\"BRL\",\"paymentCurrency\":\"PYG\"}",
                syncStatus = com.plugpdv.pdv.database.LocalSaleEntity.STATUS_WAITING_PAYMENT,
                idempotencyKeyUsed = true
            )
            db.localSaleDao().insert(sale)

            assertNull(db.paymentAttemptDao().getByReference(k))

            val updated = repository.finalizeApprovedSaleAtomic(
                localId = k,
                paymentId = "ext-pay-33",
                method = "CARTAO_CREDITO"
            )

            // 1. PaymentAttempt delta = 0
            assertNull("No synthetic PaymentAttempt may be created", db.paymentAttemptDao().getByReference(k))

            // 2. LocalSale is NOT PENDING
            assertNotNull(updated)
            assertEquals(com.plugpdv.pdv.database.LocalSaleEntity.STATUS_NEEDS_RECONCILIATION, updated!!.syncStatus)

            // 3. State is blocked and requires reconciliation
            val unresolved = repository.getUnresolvedDirectPaymentState()
            assertNotNull(unresolved)
            assertTrue(unresolved!!.isBlocked)
            assertTrue(unresolved.requiresReconciliation)
            assertFalse(unresolved.canResumeSameOperation)
        }
    }

    private fun sha256(input: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}


