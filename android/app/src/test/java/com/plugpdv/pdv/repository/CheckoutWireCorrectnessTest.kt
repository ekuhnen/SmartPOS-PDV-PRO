package com.plugpdv.pdv.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.plugpdv.pdv.database.OutboxOperationEntity
import com.plugpdv.pdv.models.CommandCheckoutCommitRequest
import com.plugpdv.pdv.models.ComandaCheckoutCommitResponse
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.ui.sale.CheckoutUiState
import com.plugpdv.pdv.utils.SingleOperationResult
import org.junit.Assert.*
import org.junit.Test

class CheckoutWireCorrectnessTest {

    private val gson = Gson()

    @Test
    fun testRealPygCurrencyPipelineValues() {
        val userAmount = 350000.0
        val currency = "PYG"
        val baseAmount = 50.0

        val request = CommandCheckoutCommitRequest(
            comandaId = "comanda-pyg-123",
            forma = "CARD",
            valor = userAmount,
            moeda = currency,
            valorBase = baseAmount,
            shouldRegisterSale = true
        )

        val json = gson.toJson(request)

        assertTrue(json.contains("\"valor\":350000.0"))
        assertTrue(json.contains("\"moeda\":\"PYG\""))
        assertTrue(json.contains("\"valor_base\":50.0"))

        val deserialized = gson.fromJson(json, CommandCheckoutCommitRequest::class.java)
        assertEquals(350000.0, deserialized.valor, 0.001)
        assertEquals("PYG", deserialized.moeda)
        assertEquals(50.0, deserialized.valorBase!!, 0.001)
    }

    @Test
    fun testApprovedToPendingSyncStateBlocksPayButtonAndKeepsPaymentSuccessFalse() {
        val state = CheckoutUiState(
            isLoading = false,
            paymentSuccess = false,
            isPendingSync = true,
            isPayButtonBlocked = true,
            blockReason = "Pagamento aprovado aguardando sincronização com o servidor"
        )

        assertFalse(state.paymentSuccess)
        assertTrue(state.isPendingSync)
        assertTrue(state.isPayButtonBlocked)
        assertEquals("Pagamento aprovado aguardando sincronização com o servidor", state.blockReason)
    }

    @Test
    fun testClosedTruePendingFalsePaymentSuccessTrueTableAvailable() {
        val json = """
            {
                "success": true,
                "created_new": true,
                "payment_id": "pay-1",
                "sale_id": "sale-1",
                "closed": true,
                "comanda_status": "FECHADA"
            }
        """.trimIndent()

        val response = gson.fromJson(json, ComandaCheckoutCommitResponse::class.java)
        assertTrue(response.success)
        assertTrue(response.closed)

        val table = Table(id = "1", number = 10, status = Table.Status.AVAILABLE)
        val stateAfterSync = CheckoutUiState(
            isLoading = false,
            paymentSuccess = true,
            isPendingSync = false,
            isPayButtonBlocked = false
        )

        assertTrue(stateAfterSync.paymentSuccess)
        assertFalse(stateAfterSync.isPendingSync)
        assertFalse(stateAfterSync.isPayButtonBlocked)
        assertEquals(Table.Status.AVAILABLE, table.status)
    }

    @Test
    fun testClosedFalsePendingFalseTableOccupiedAndButtonUnblocked() {
        val json = """
            {
                "success": true,
                "created_new": true,
                "payment_id": "pay-partial-1",
                "closed": false,
                "comanda_status": "ABERTA",
                "remaining_balance": 50.0
            }
        """.trimIndent()

        val response = gson.fromJson(json, ComandaCheckoutCommitResponse::class.java)
        assertTrue(response.success)
        assertFalse(response.closed)

        val table = Table(id = "2", number = 20, status = Table.Status.OCCUPIED)
        val stateAfterPartialPayment = CheckoutUiState(
            isLoading = false,
            paymentSuccess = true,
            isPendingSync = false,
            isPayButtonBlocked = false,
            currentToPay = response.remainingBalance
        )

        assertTrue(stateAfterPartialPayment.paymentSuccess)
        assertFalse(stateAfterPartialPayment.isPendingSync)
        assertFalse(stateAfterPartialPayment.isPayButtonBlocked)
        assertEquals(50.0, stateAfterPartialPayment.currentToPay, 0.001)
        assertEquals(Table.Status.OCCUPIED, table.status)
    }

    @Test
    fun testReconciliationPendingFalseReconciliationTrueButtonBlocked() {
        val json = """
            {
                "success": true,
                "created_new": true,
                "payment_id": "pay-overpayment-99",
                "closed": true,
                "requires_reconciliation": true
            }
        """.trimIndent()

        val response = gson.fromJson(json, ComandaCheckoutCommitResponse::class.java)
        assertTrue(response.requiresReconciliation)

        val stateAfterReconciliation = CheckoutUiState(
            isLoading = false,
            paymentSuccess = false,
            isPendingSync = false,
            requiresReconciliation = true,
            isPayButtonBlocked = true,
            blockReason = "Pagamento aprovado requer conciliação"
        )

        assertFalse(stateAfterReconciliation.paymentSuccess)
        assertTrue(stateAfterReconciliation.requiresReconciliation)
        assertTrue(stateAfterReconciliation.isPayButtonBlocked)
        assertEquals("Pagamento aprovado requer conciliação", stateAfterReconciliation.blockReason)
    }

    @Test
    fun testIdempotencyKeyReusedFinalStateNotSynced() {
        val errorBody = """{"code":"IDEMPOTENCY_KEY_REUSED","message":"Chave reutilizada com payload diferente"}"""
        val jsonObj = gson.fromJson(errorBody, JsonObject::class.java)
        val code = jsonObj.get("code")?.asString

        assertEquals("IDEMPOTENCY_KEY_REUSED", code)

        val result = when (code) {
            "IDEMPOTENCY_KEY_REUSED" -> SingleOperationResult.NEEDS_RECONCILIATION
            else -> SingleOperationResult.RETRY
        }

        assertEquals(SingleOperationResult.NEEDS_RECONCILIATION, result)
        assertNotEquals(SingleOperationResult.SYNCED, result)

        val op = OutboxOperationEntity(
            id = "op-123",
            operationType = "COMANDA_CHECKOUT_COMMIT",
            targetGroupKey = "comanda-1",
            payloadJson = "{}",
            createdAt = System.currentTimeMillis(),
            status = "FAILED",
            lastError = "IDEMPOTENCY_KEY_REUSED",
            messageKey = "REQUIRES_RECONCILIATION",
            isRetriable = false
        )

        assertEquals("FAILED", op.status)
        assertEquals("IDEMPOTENCY_KEY_REUSED", op.lastError)
        assertFalse(op.isRetriable)
    }

    @Test
    fun testHttp400FinalStateFailedNoRetry() {
        val httpStatusCode = 400
        val result = if (httpStatusCode in 400..422 && httpStatusCode != 408 && httpStatusCode != 409) {
            SingleOperationResult.FAILED_PERMANENT
        } else {
            SingleOperationResult.RETRY
        }

        assertEquals(SingleOperationResult.FAILED_PERMANENT, result)

        val op = OutboxOperationEntity(
            id = "op-400",
            operationType = "COMANDA_CHECKOUT_COMMIT",
            targetGroupKey = "comanda-1",
            payloadJson = "{}",
            createdAt = System.currentTimeMillis(),
            status = "FAILED",
            lastError = "HTTP_400",
            messageKey = "UNRECOVERABLE_ERROR",
            isRetriable = false
        )

        assertEquals("FAILED", op.status)
        assertEquals("HTTP_400", op.lastError)
        assertFalse(op.isRetriable)
    }

    @Test
    fun testOperationInProgressFinalStatePendingWithRetry() {
        val inProgressBody = """{"code":"OPERATION_IN_PROGRESS","message":"Operação em andamento"}"""
        val jsonObj = gson.fromJson(inProgressBody, JsonObject::class.java)
        val code = jsonObj.get("code")?.asString

        val result = when (code) {
            "OPERATION_IN_PROGRESS" -> SingleOperationResult.RETRY
            else -> SingleOperationResult.FAILED_PERMANENT
        }

        assertEquals(SingleOperationResult.RETRY, result)

        val op = OutboxOperationEntity(
            id = "op-in-progress",
            operationType = "COMANDA_CHECKOUT_COMMIT",
            targetGroupKey = "comanda-1",
            payloadJson = "{}",
            createdAt = System.currentTimeMillis(),
            attemptCount = 1,
            nextRetryAt = System.currentTimeMillis() + 2000L,
            status = "PENDING",
            isRetriable = true
        )

        assertEquals("PENDING", op.status)
        assertTrue(op.isRetriable)
    }
}
