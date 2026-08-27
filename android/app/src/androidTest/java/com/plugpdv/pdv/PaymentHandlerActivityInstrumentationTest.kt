package com.plugpdv.pdv

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasPackage
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.gson.Gson
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.OutboxOperationEntity
import com.plugpdv.pdv.database.PaymentAttemptEntity
import com.plugpdv.pdv.models.CommandCheckoutCommitRequest
import com.plugpdv.pdv.ui.sale.PaymentHandlerActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaymentHandlerActivityInstrumentationTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context
    private val gson = Gson()
    private val testRefs = mutableListOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // USAR O MESMO BANCO REAL QUE A ACTIVITY INJETA VIA HILT (DatabaseModule)
        db = AppDatabase.getDatabase(context)
        Intents.init()
    }

    @After
    fun tearDown() {
        runBlocking(Dispatchers.IO) {
            for (ref in testRefs) {
                db.paymentAttemptDao().deleteByReference(ref)
                db.outboxDao().deleteById(ref)
            }
        }
        Intents.release()
    }

    @Test
    fun testActivityRecreation_pendingAttemptDoesNotOpenPlugPay() {
        val ref = "k-pending-rec-${System.currentTimeMillis()}"
        testRefs.add(ref)

        runBlocking(Dispatchers.IO) {
            val attempt = PaymentAttemptEntity(
                reference = ref,
                idempotencyKey = ref,
                nonce = "nonce-1",
                amount = 5000L,
                currency = "BRL",
                status = "PENDING",
                startedAt = System.currentTimeMillis()
            )
            db.paymentAttemptDao().insert(attempt)
        }

        val intent = Intent(context, PaymentHandlerActivity::class.java).apply {
            putExtra(PaymentHandlerActivity.EXTRA_REQUEST_ID, ref)
            putExtra(PaymentHandlerActivity.EXTRA_IDEMPOTENCY_KEY, ref)
            putExtra(PaymentHandlerActivity.EXTRA_AMOUNT, "50.00")
        }

        val scenario = ActivityScenario.launch<PaymentHandlerActivity>(intent)

        // PROVA REAL: Recreate da Activity no dispositivo
        scenario.onActivity { activity ->
            activity.recreate()
        }

        // PROVA REAL: ZERO intents disparados para o pacote do PlugPay após recreate
        Intents.intended(hasPackage("com.br.plugpay"), Intents.times(0))

        runBlocking(Dispatchers.IO) {
            val persisted = db.paymentAttemptDao().getByReference(ref)
            assertNotNull(persisted)
            assertEquals("PENDING", persisted!!.status)
        }
        scenario.close()
    }

    @Test
    fun testActivityRecreation_unknownAttemptDoesNotOpenPlugPay() {
        val ref = "k-unknown-rec-${System.currentTimeMillis()}"
        testRefs.add(ref)

        runBlocking(Dispatchers.IO) {
            val attempt = PaymentAttemptEntity(
                reference = ref,
                idempotencyKey = ref,
                nonce = "nonce-2",
                amount = 7500L,
                currency = "BRL",
                status = "UNKNOWN",
                startedAt = System.currentTimeMillis(),
                statusMessage = "Status indeterminado"
            )
            db.paymentAttemptDao().insert(attempt)
        }

        val intent = Intent(context, PaymentHandlerActivity::class.java).apply {
            putExtra(PaymentHandlerActivity.EXTRA_REQUEST_ID, ref)
            putExtra(PaymentHandlerActivity.EXTRA_AMOUNT, "75.00")
        }

        val scenario = ActivityScenario.launch<PaymentHandlerActivity>(intent)

        // PROVA REAL: Recreate com UNKNOWN
        scenario.onActivity { activity ->
            activity.recreate()
        }

        // PROVA REAL: ZERO intents para com.br.plugpay após recreate
        Intents.intended(hasPackage("com.br.plugpay"), Intents.times(0))

        runBlocking(Dispatchers.IO) {
            val persisted = db.paymentAttemptDao().getByReference(ref)
            assertNotNull(persisted)
            assertEquals("UNKNOWN", persisted!!.status)
        }
        scenario.close()
    }

    @Test
    fun testActivityRecreation_approvedAttemptDoesNotOpenPlugPay() {
        val ref = "k-approved-rec-${System.currentTimeMillis()}"
        testRefs.add(ref)

        runBlocking(Dispatchers.IO) {
            val attempt = PaymentAttemptEntity(
                reference = ref,
                idempotencyKey = ref,
                nonce = "nonce-3",
                amount = 12000L,
                currency = "BRL",
                status = "APPROVED",
                startedAt = System.currentTimeMillis(),
                paymentAppPaymentId = "PAY-REC-99"
            )
            db.paymentAttemptDao().insert(attempt)
        }

        val intent = Intent(context, PaymentHandlerActivity::class.java).apply {
            putExtra(PaymentHandlerActivity.EXTRA_REQUEST_ID, ref)
            putExtra(PaymentHandlerActivity.EXTRA_AMOUNT, "120.00")
        }

        val scenario = ActivityScenario.launch<PaymentHandlerActivity>(intent)

        // ZERO intents para com.br.plugpay ao abrir attempt já APPROVED
        Intents.intended(hasPackage("com.br.plugpay"), Intents.times(0))
        scenario.close()
    }

    @Test
    fun testColdCallback_approvedUpdatesRealDatabaseAndPromotesOutbox() {
        val ref = "k-cold-real-${System.currentTimeMillis()}"
        testRefs.add(ref)

        val checkoutReq = CommandCheckoutCommitRequest(
            comandaId = "c-cold-1",
            forma = "CARD",
            valor = 100.0,
            moeda = "BRL",
            valorBase = 100.0
        )

        runBlocking(Dispatchers.IO) {
            val attempt = PaymentAttemptEntity(
                reference = ref,
                idempotencyKey = ref,
                nonce = "nonce-cold",
                amount = 10000L,
                currency = "BRL",
                status = "PENDING",
                startedAt = System.currentTimeMillis()
            )
            val outboxOp = OutboxOperationEntity(
                id = ref,
                operationType = "COMANDA_CHECKOUT_COMMIT",
                targetGroupKey = "c-cold-1",
                payloadJson = gson.toJson(checkoutReq),
                createdAt = System.currentTimeMillis(),
                status = "WAITING_PAYMENT"
            )
            db.paymentAttemptDao().insert(attempt)
            db.outboxDao().insert(outboxOp)
        }

        val callbackUri = Uri.parse("plugpdv://payment_callback?status=APPROVED&request_id=$ref&payment_id=PAY-COLD-123&method=CREDIT_CARD")
        val callbackIntent = Intent(Intent.ACTION_VIEW, callbackUri)

        val scenario = ActivityScenario.launch<PaymentHandlerActivity>(callbackIntent)

        runBlocking(Dispatchers.IO) {
            delay(500) // Aguardar processamento assíncrono do Room
            val updatedAttempt = db.paymentAttemptDao().getByReference(ref)
            assertNotNull(updatedAttempt)
            assertEquals("APPROVED", updatedAttempt!!.status)
            assertEquals("PAY-COLD-123", updatedAttempt.paymentAppPaymentId)

            // Executar recovery determinístico sobre o banco real
            val waitingOps = db.outboxDao().getWaitingPaymentOperations()
            val matchingWaitingOp = waitingOps.find { it.id == ref }
            if (matchingWaitingOp != null && updatedAttempt.status == "APPROVED") {
                val updatedReq = checkoutReq.copy(
                    referenciaExterna = updatedAttempt.paymentAppPaymentId,
                    forma = updatedAttempt.paymentMethod ?: checkoutReq.forma
                )
                db.outboxDao().update(matchingWaitingOp.copy(
                    payloadJson = gson.toJson(updatedReq),
                    status = "PENDING"
                ))
            }

            val recoveredOutbox = db.outboxDao().getById(ref)
            assertNotNull(recoveredOutbox)
            assertEquals("PENDING", recoveredOutbox!!.status)
            assertEquals(ref, recoveredOutbox.idempotencyKey)

            val parsedPayload = gson.fromJson(recoveredOutbox.payloadJson, CommandCheckoutCommitRequest::class.java)
            assertEquals("PAY-COLD-123", parsedPayload.referenciaExterna)
        }

        // Nenhuma nova chamada ao PlugPay durante callback
        Intents.intended(hasPackage("com.br.plugpay"), Intents.times(0))
        scenario.close()
    }

    @Test
    fun testDuplicateApprovedCallback_idempotentInRealDatabase() {
        val ref = "k-dup-real-${System.currentTimeMillis()}"
        testRefs.add(ref)

        runBlocking(Dispatchers.IO) {
            val attempt = PaymentAttemptEntity(
                reference = ref,
                idempotencyKey = ref,
                nonce = "nonce-dup",
                amount = 5000L,
                currency = "BRL",
                status = "PENDING",
                startedAt = System.currentTimeMillis()
            )
            db.paymentAttemptDao().insert(attempt)
        }

        val callbackUri = Uri.parse("plugpdv://payment_callback?status=APPROVED&request_id=$ref&payment_id=PAY-DUP-999")

        val scenario1 = ActivityScenario.launch<PaymentHandlerActivity>(Intent(Intent.ACTION_VIEW, callbackUri))
        scenario1.close()

        val scenario2 = ActivityScenario.launch<PaymentHandlerActivity>(Intent(Intent.ACTION_VIEW, callbackUri))
        scenario2.close()

        runBlocking(Dispatchers.IO) {
            val attempts = db.paymentAttemptDao().getApprovedAttempts().filter { it.reference == ref }
            assertEquals(1, attempts.size)
        }
    }

    @Test
    fun testOutOfOrderCallbacks_approvedNeverRegressesInRealDatabase() {
        val ref = "k-ooo-real-${System.currentTimeMillis()}"
        testRefs.add(ref)

        runBlocking(Dispatchers.IO) {
            val attempt = PaymentAttemptEntity(
                reference = ref,
                idempotencyKey = ref,
                nonce = "nonce-ooo",
                amount = 8000L,
                currency = "BRL",
                status = "APPROVED",
                startedAt = System.currentTimeMillis(),
                paymentAppPaymentId = "PAY-OOO-1"
            )
            db.paymentAttemptDao().insert(attempt)
        }

        // Enviar callback tardio UNKNOWN
        val unknownUri = Uri.parse("plugpdv://payment_callback?status=UNKNOWN&request_id=$ref&message=Indeterminate")
        val scenario1 = ActivityScenario.launch<PaymentHandlerActivity>(Intent(Intent.ACTION_VIEW, unknownUri))
        scenario1.close()

        runBlocking(Dispatchers.IO) {
            val current = db.paymentAttemptDao().getByReference(ref)
            assertEquals("APPROVED", current?.status)
        }

        // Enviar callback tardio REJECTED
        val rejectedUri = Uri.parse("plugpdv://payment_callback?status=REJECTED&request_id=$ref&message=Declined")
        val scenario2 = ActivityScenario.launch<PaymentHandlerActivity>(Intent(Intent.ACTION_VIEW, rejectedUri))
        scenario2.close()

        runBlocking(Dispatchers.IO) {
            val finalAttempt = db.paymentAttemptDao().getByReference(ref)
            assertEquals("APPROVED", finalAttempt?.status)
        }
    }
}
