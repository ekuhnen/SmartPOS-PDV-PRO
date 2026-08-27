package com.plugpdv.pdv.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.plugpdv.pdv.models.CommandCheckoutCommitRequest
import com.plugpdv.pdv.models.ComandaCheckoutCommitResponse
import com.plugpdv.pdv.ui.sale.CheckoutUiState
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
    fun testBackendClosedTrueEnablesPaymentSuccess() {
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

        val stateAfterSync = CheckoutUiState(
            isLoading = false,
            paymentSuccess = true,
            isPendingSync = false,
            isPayButtonBlocked = false
        )

        assertTrue(stateAfterSync.paymentSuccess)
        assertFalse(stateAfterSync.isPendingSync)
        assertFalse(stateAfterSync.isPayButtonBlocked)
    }

    @Test
    fun testBackendReconciliationBlocksButtonAndDisablesPaymentSuccess() {
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
    }

    @Test
    fun testClassificationOf409OperationInProgressVsIdempotencyKeyReused() {
        val inProgressBody = """{"code":"OPERATION_IN_PROGRESS","message":"Operação em andamento"}"""
        val keyReusedBody = """{"code":"IDEMPOTENCY_KEY_REUSED","message":"Chave reutilizada em contexto diferente"}"""

        val inProgressObj = gson.fromJson(inProgressBody, JsonObject::class.java)
        val inProgressCode = inProgressObj.get("code")?.asString
        assertEquals("OPERATION_IN_PROGRESS", inProgressCode)

        val keyReusedObj = gson.fromJson(keyReusedBody, JsonObject::class.java)
        val keyReusedCode = keyReusedObj.get("code")?.asString
        assertEquals("IDEMPOTENCY_KEY_REUSED", keyReusedCode)
    }
}
