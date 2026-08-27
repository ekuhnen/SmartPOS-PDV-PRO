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
}
