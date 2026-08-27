package com.plugpdv.pdv.repository

import com.google.gson.Gson
import com.plugpdv.pdv.models.CommandCheckoutCommitRequest
import com.plugpdv.pdv.models.ComandaCheckoutCommitResponse
import com.plugpdv.pdv.ui.sale.CheckoutUiState
import org.junit.Assert.*
import org.junit.Test

class CheckoutWireCorrectnessTest {

    private val gson = Gson()

    @Test
    fun testPygTransactionAmountVersusBaseAmountSerialization() {
        val request = CommandCheckoutCommitRequest(
            comandaId = "comanda-100",
            forma = "CARD",
            valor = 350000.0,
            moeda = "PYG",
            valorBase = 50.0,
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
    fun testPendingSyncStateDoesNotTriggerPaymentSuccess() {
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
        assertNotNull(state.blockReason)
    }

    @Test
    fun testReconciliationResponseState() {
        val json = """
            {
                "success": true,
                "created_new": true,
                "payment_id": "pay-99",
                "comanda_status": "FECHADA",
                "closed": true,
                "requires_reconciliation": true
            }
        """.trimIndent()

        val response = gson.fromJson(json, ComandaCheckoutCommitResponse::class.java)

        assertTrue(response.success)
        assertTrue(response.closed)
        assertTrue(response.requiresReconciliation)

        val state = CheckoutUiState(
            isLoading = false,
            paymentSuccess = false,
            isPendingSync = false,
            requiresReconciliation = response.requiresReconciliation
        )

        assertFalse(state.paymentSuccess)
        assertTrue(state.requiresReconciliation)
    }
}
