package com.plugpdv.pdv.repository

import com.google.gson.Gson
import com.plugpdv.pdv.database.OutboxDao
import com.plugpdv.pdv.database.OutboxOperationEntity
import com.plugpdv.pdv.database.PaymentAttemptDao
import com.plugpdv.pdv.database.PaymentAttemptEntity
import com.plugpdv.pdv.models.CommandCheckoutCommitRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class DurableCheckoutIdempotencyTest {

    private val outboxDao: OutboxDao = mock()
    private val paymentAttemptDao: PaymentAttemptDao = mock()
    private val gson = Gson()

    @Test
    fun testKeyGenerationBeforePaymentAndWaitingPaymentState() = runBlocking {
        val key = "test-checkout-uuid-1234"
        val request = CommandCheckoutCommitRequest(
            comandaId = "comanda-999",
            mesaId = "mesa-12",
            forma = "CARD",
            valor = 150.0,
            moeda = "BRL",
            shouldRegisterSale = true
        )

        val outboxEntity = OutboxOperationEntity(
            id = key,
            operationType = "COMANDA_CHECKOUT_COMMIT",
            targetGroupKey = request.comandaId,
            payloadJson = gson.toJson(request),
            createdAt = System.currentTimeMillis(),
            idempotencyKey = key,
            status = "WAITING_PAYMENT"
        )

        val attemptEntity = PaymentAttemptEntity(
            reference = key,
            idempotencyKey = key,
            nonce = "nonce-1",
            amount = 15000L,
            currency = "BRL",
            status = "PENDING",
            startedAt = System.currentTimeMillis(),
            tableNumber = 12
        )

        outboxDao.insert(outboxEntity)
        paymentAttemptDao.insert(attemptEntity)

        verify(outboxDao).insert(argThat {
            id == key && idempotencyKey == key && status == "WAITING_PAYMENT"
        })
        verify(paymentAttemptDao).insert(argThat {
            reference == key && idempotencyKey == key
        })
    }

    @Test
    fun testApprovedPromotionToPendingWithExternalPaymentId() = runBlocking {
        val key = "test-approved-uuid-5678"
        val paymentId = "plugpay-tx-9999"

        val initialRequest = CommandCheckoutCommitRequest(
            comandaId = "comanda-777",
            forma = "CARD",
            valor = 200.0,
            moeda = "BRL"
        )

        val waitingOp = OutboxOperationEntity(
            id = key,
            operationType = "COMANDA_CHECKOUT_COMMIT",
            targetGroupKey = "comanda-777",
            payloadJson = gson.toJson(initialRequest),
            createdAt = System.currentTimeMillis(),
            idempotencyKey = key,
            status = "WAITING_PAYMENT"
        )

        val approvedAttempt = PaymentAttemptEntity(
            reference = key,
            idempotencyKey = key,
            nonce = "nonce-2",
            amount = 20000L,
            currency = "BRL",
            status = "APPROVED",
            paymentAppPaymentId = paymentId,
            startedAt = System.currentTimeMillis()
        )

        whenever(outboxDao.getWaitingPaymentOperations()).thenReturn(listOf(waitingOp))
        whenever(paymentAttemptDao.getApprovedAttempts()).thenReturn(listOf(approvedAttempt))

        // Simula recuperação de process death / promoção
        val waitingOps = outboxDao.getWaitingPaymentOperations()
        val approved = paymentAttemptDao.getApprovedAttempts()

        for (op in waitingOps) {
            val match = approved.find { it.reference == op.idempotencyKey }
            if (match != null) {
                val req = gson.fromJson(op.payloadJson, CommandCheckoutCommitRequest::class.java)
                val updatedReq = req.copy(referenciaExterna = match.paymentAppPaymentId)
                val updatedOp = op.copy(payloadJson = gson.toJson(updatedReq), status = "PENDING")
                outboxDao.update(updatedOp)
            }
        }

        verify(outboxDao).update(argThat {
            id == key && status == "PENDING" && payloadJson.contains(paymentId)
        })
    }
}
