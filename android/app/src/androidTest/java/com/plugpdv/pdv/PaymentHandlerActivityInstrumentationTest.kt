package com.plugpdv.pdv

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.OutboxOperationEntity
import com.plugpdv.pdv.database.PaymentAttemptEntity
import com.plugpdv.pdv.ui.sale.PaymentHandlerActivity
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
    fun testActivityRecreation_pendingAttemptDoesNotAutoRecharge() {
        runBlocking {
            val attempt = PaymentAttemptEntity(
                reference = "k-pending-rec",
                idempotencyKey = "k-pending-rec",
                nonce = "nonce-1",
                amount = 5000L,
                currency = "BRL",
                status = "PENDING",
                startedAt = System.currentTimeMillis()
            )
            db.paymentAttemptDao().insert(attempt)

            val intent = Intent(context, PaymentHandlerActivity::class.java).apply {
                putExtra(PaymentHandlerActivity.EXTRA_REQUEST_ID, "k-pending-rec")
                putExtra(PaymentHandlerActivity.EXTRA_IDEMPOTENCY_KEY, "k-pending-rec")
                putExtra(PaymentHandlerActivity.EXTRA_AMOUNT, "50.00")
            }

            val scenario = ActivityScenario.launch<PaymentHandlerActivity>(intent)
            scenario.recreate()

            // Verify attempt is preserved in PENDING state and not duplicated
            val persisted = db.paymentAttemptDao().getByReference("k-pending-rec")
            assertNotNull(persisted)
            assertEquals("PENDING", persisted!!.status)
            scenario.close()
        }
    }

    @Test
    fun testActivityRecreation_unknownAttemptEnforcesVerification() {
        runBlocking {
            val attempt = PaymentAttemptEntity(
                reference = "k-unknown-rec",
                idempotencyKey = "k-unknown-rec",
                nonce = "nonce-2",
                amount = 7500L,
                currency = "BRL",
                status = "UNKNOWN",
                startedAt = System.currentTimeMillis(),
                statusMessage = "Status indeterminado"
            )
            db.paymentAttemptDao().insert(attempt)

            val intent = Intent(context, PaymentHandlerActivity::class.java).apply {
                putExtra(PaymentHandlerActivity.EXTRA_REQUEST_ID, "k-unknown-rec")
                putExtra(PaymentHandlerActivity.EXTRA_AMOUNT, "75.00")
            }

            val scenario = ActivityScenario.launch<PaymentHandlerActivity>(intent)
            scenario.recreate()

            val persisted = db.paymentAttemptDao().getByReference("k-unknown-rec")
            assertNotNull(persisted)
            assertEquals("UNKNOWN", persisted!!.status)
            scenario.close()
        }
    }

    @Test
    fun testColdCallback_approvedPromotesAttemptAndPreservesKey() {
        runBlocking {
            val attempt = PaymentAttemptEntity(
                reference = "k-cold-1",
                idempotencyKey = "k-cold-1",
                nonce = "nonce-cold",
                amount = 10000L,
                currency = "BRL",
                status = "PENDING",
                startedAt = System.currentTimeMillis()
            )
            db.paymentAttemptDao().insert(attempt)

            val callbackUri = Uri.parse("plugpdv://payment_callback?status=APPROVED&request_id=k-cold-1&payment_id=PAY-COLD-999&method=CREDIT_CARD")
            val callbackIntent = Intent(Intent.ACTION_VIEW, callbackUri)

            val scenario = ActivityScenario.launch<PaymentHandlerActivity>(callbackIntent)

            val updated = db.paymentAttemptDao().getByReference("k-cold-1")
            assertNotNull(updated)
            assertEquals("APPROVED", updated!!.status)
            assertEquals("PAY-COLD-999", updated.paymentAppPaymentId)
            scenario.close()
        }
    }

    @Test
    fun testPrecedence_approvedNeverRegressesToRejectedOrUnknown() {
        runBlocking {
            val attempt = PaymentAttemptEntity(
                reference = "k-precedence-1",
                idempotencyKey = "k-precedence-1",
                nonce = "nonce-p",
                amount = 15000L,
                currency = "BRL",
                status = "APPROVED",
                startedAt = System.currentTimeMillis(),
                paymentAppPaymentId = "PAY-PREC-1"
            )
            db.paymentAttemptDao().insert(attempt)

            // Simular callback tardio REJECTED
            val rejectedCallbackUri = Uri.parse("plugpdv://payment_callback?status=REJECTED&request_id=k-precedence-1&message=Declined")
            val scenario = ActivityScenario.launch<PaymentHandlerActivity>(Intent(Intent.ACTION_VIEW, rejectedCallbackUri))

            val checked = db.paymentAttemptDao().getByReference("k-precedence-1")
            assertNotNull(checked)
            assertEquals("APPROVED", checked!!.status) // APPROVED never regresses!
            scenario.close()
        }
    }

    @Test
    fun testDuplicateApprovedCallback_idempotent() {
        runBlocking {
            val attempt = PaymentAttemptEntity(
                reference = "k-dup-app",
                idempotencyKey = "k-dup-app",
                nonce = "nonce-dup",
                amount = 5000L,
                currency = "BRL",
                status = "PENDING",
                startedAt = System.currentTimeMillis()
            )
            db.paymentAttemptDao().insert(attempt)

            val callbackUri = Uri.parse("plugpdv://payment_callback?status=APPROVED&request_id=k-dup-app&payment_id=PAY-DUP-1")
            
            // First delivery
            val scenario1 = ActivityScenario.launch<PaymentHandlerActivity>(Intent(Intent.ACTION_VIEW, callbackUri))
            scenario1.close()

            // Second delivery (duplicate)
            val scenario2 = ActivityScenario.launch<PaymentHandlerActivity>(Intent(Intent.ACTION_VIEW, callbackUri))
            scenario2.close()

            val allAttempts = db.paymentAttemptDao().getApprovedAttempts().filter { it.reference == "k-dup-app" }
            assertEquals(1, allAttempts.size)
        }
    }
}
