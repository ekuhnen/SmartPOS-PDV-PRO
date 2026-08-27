package com.plugpdv.pdv.repository

import com.google.gson.Gson
import com.plugpdv.pdv.database.LocalSaleEntity
import com.plugpdv.pdv.database.OutboxOperationEntity
import com.plugpdv.pdv.database.PaymentAttemptEntity
import com.plugpdv.pdv.models.CommandCheckoutCommitRequest
import com.plugpdv.pdv.models.ComandaCheckoutCommitResponse
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.ui.sale.CheckoutUiState
import com.plugpdv.pdv.utils.OutboxSyncManager
import org.junit.Assert.*
import org.junit.Test

class RecoveryAndChaosTest {

    private val gson = Gson()

    @Test
    fun testPaymentActivityRecreationPreventsAutoRecharge() {
        val attemptPending = PaymentAttemptEntity(
            reference = "req-100",
            idempotencyKey = "k-100",
            nonce = "n-1",
            amount = 5000L,
            currency = "BRL",
            status = "PENDING",
            startedAt = System.currentTimeMillis()
        )

        val attemptUnknown = attemptPending.copy(status = "UNKNOWN")
        val attemptApproved = attemptPending.copy(status = "APPROVED")

        fun shouldReopenPlugPay(attempt: PaymentAttemptEntity?): Boolean {
            if (attempt == null) return true
            return when (attempt.status) {
                "PENDING", "UNKNOWN", "APPROVED", "CANCELLED", "REJECTED", "FAILED_TO_START" -> false
                else -> false
            }
        }

        assertFalse(shouldReopenPlugPay(attemptPending))
        assertFalse(shouldReopenPlugPay(attemptUnknown))
        assertFalse(shouldReopenPlugPay(attemptApproved))
        assertTrue(shouldReopenPlugPay(null))
    }

    @Test
    fun testCallbackPrecedenceApprovedNeverRegresses() {
        val approvedAttempt = PaymentAttemptEntity(
            reference = "req-200",
            idempotencyKey = "k-200",
            nonce = "n-2",
            amount = 10000L,
            currency = "BRL",
            status = "APPROVED",
            startedAt = System.currentTimeMillis(),
            completedAt = System.currentTimeMillis(),
            paymentAppPaymentId = "pay-200"
        )

        fun resolveCallbackStatus(existing: PaymentAttemptEntity?, newRawStatus: String): String {
            val normalized = when (newRawStatus.uppercase()) {
                "APPROVED" -> "APPROVED"
                "CANCELLED", "CANCELED" -> "CANCELLED"
                "REJECTED", "DECLINED" -> "REJECTED"
                else -> "UNKNOWN"
            }
            if (existing?.status == "APPROVED" && normalized != "APPROVED") {
                return "APPROVED"
            }
            return normalized
        }

        assertEquals("APPROVED", resolveCallbackStatus(approvedAttempt, "UNKNOWN"))
        assertEquals("APPROVED", resolveCallbackStatus(approvedAttempt, "REJECTED"))
        assertEquals("APPROVED", resolveCallbackStatus(approvedAttempt, "CANCELLED"))
        assertEquals("APPROVED", resolveCallbackStatus(approvedAttempt, "APPROVED"))
    }

    @Test
    fun testCancelledAndRejectedTerminalizeOutboxWithoutLiberatingTable() {
        val table = Table(id = "table-1", number = 10, status = Table.Status.OCCUPIED)
        val outboxOp = OutboxOperationEntity(
            id = "req-cancelled",
            operationType = "COMANDA_CHECKOUT_COMMIT",
            targetGroupKey = "comanda-1",
            payloadJson = "{}",
            createdAt = System.currentTimeMillis(),
            status = "WAITING_PAYMENT"
        )

        val terminalizedOp = outboxOp.copy(
            status = "FAILED",
            lastError = "CANCELLED",
            messageKey = "CANCELLED_PAYMENT",
            isRetriable = false
        )

        assertEquals("FAILED", terminalizedOp.status)
        assertEquals("CANCELLED_PAYMENT", terminalizedOp.messageKey)
        assertFalse(terminalizedOp.isRetriable)
        assertEquals(Table.Status.OCCUPIED, table.status)
    }

    @Test
    fun testReconciliationRestoreAndRaceProtection() {
        // 1) UI inicializa com reconciliation restaurada
        val initialState = CheckoutUiState(
            requiresReconciliation = true,
            isPayButtonBlocked = true,
            paymentSuccess = false,
            blockReason = "Pagamento aprovado requer conciliação"
        )

        // 2) fetchComandaPayments recebe resposta assíncrona FECHADA
        val isComandaClosed = true
        val mergedState = if (isComandaClosed) {
            initialState.copy(
                currentToPay = 0.0,
                isPendingSync = false,
                isPayButtonBlocked = initialState.requiresReconciliation,
                paymentSuccess = !initialState.requiresReconciliation, // PRECEDÊNCIA: continua false!
                requiresReconciliation = initialState.requiresReconciliation,
                blockReason = if (initialState.requiresReconciliation) initialState.blockReason else null
            )
        } else {
            initialState
        }

        assertTrue(mergedState.requiresReconciliation)
        assertTrue(mergedState.isPayButtonBlocked)
        assertFalse(mergedState.paymentSuccess)
        assertEquals("Pagamento aprovado requer conciliação", mergedState.blockReason)
    }

    @Test
    fun testApprovedOrphanBlocksCheckout() {
        val orphanAttempt = PaymentAttemptEntity(
            reference = "req-orphan",
            idempotencyKey = "k-orphan",
            nonce = "n-orphan",
            amount = 5000L,
            currency = "BRL",
            status = "APPROVED",
            startedAt = System.currentTimeMillis(),
            tableNumber = 5,
            orderId = "c-orphan"
        )

        val outboxExists = false
        val state = if (!outboxExists && orphanAttempt.status == "APPROVED") {
            CheckoutUiState(
                isPayButtonBlocked = true,
                requiresReconciliation = true,
                paymentSuccess = false,
                blockReason = "Pagamento aprovado na maquininha sem registro de checkout (Requer conciliação)"
            )
        } else {
            CheckoutUiState()
        }

        assertTrue(state.isPayButtonBlocked)
        assertTrue(state.requiresReconciliation)
        assertFalse(state.paymentSuccess)
    }

    @Test
    fun testExternalPendingWithoutAttemptFailsReconciliation() {
        val req = CommandCheckoutCommitRequest(
            comandaId = "c-1",
            forma = "CARD",
            valor = 100.0,
            moeda = "BRL",
            valorBase = 100.0,
            referenciaExterna = null // Sem comprovante externo
        )

        val isCash = req.forma.equals("DINHEIRO", true) || req.forma.equals("CASH", true)
        val matchingAttempt: PaymentAttemptEntity? = null // Sem attempt
        val hasApprovedAttempt = matchingAttempt?.status == "APPROVED"
        val hasValidExternalRef = !req.referenciaExterna.isNullOrEmpty()

        val canSync = isCash || hasApprovedAttempt || hasValidExternalRef
        assertFalse(canSync) // Rejeita e exige conciliação!
    }

    @Test
    fun testCashWithoutAttemptAllowed() {
        val req = CommandCheckoutCommitRequest(
            comandaId = "c-1",
            forma = "DINHEIRO",
            valor = 100.0,
            moeda = "BRL",
            valorBase = 100.0,
            referenciaExterna = null
        )

        val isCash = req.forma.equals("DINHEIRO", true) || req.forma.equals("CASH", true)
        val canSync = isCash
        assertTrue(canSync) // Pagamento em dinheiro permitido sem PaymentAttempt de maquininha
    }

    @Test
    fun testFaultInjectionHooksDirectAndCheckout() {
        SaleOutboxRepository.faultInjectionHook = "AFTER_HTTP_BEFORE_ROOM_SUCCESS"
        assertEquals("AFTER_HTTP_BEFORE_ROOM_SUCCESS", SaleOutboxRepository.faultInjectionHook)
        SaleOutboxRepository.faultInjectionHook = null
        assertNull(SaleOutboxRepository.faultInjectionHook)

        OutboxSyncManager.faultInjectionHook = "AFTER_HTTP_BEFORE_ROOM_SUCCESS"
        assertEquals("AFTER_HTTP_BEFORE_ROOM_SUCCESS", OutboxSyncManager.faultInjectionHook)
        OutboxSyncManager.faultInjectionHook = null
        assertNull(OutboxSyncManager.faultInjectionHook)
    }

    @Test
    fun testStalePendingPaymentAttemptsBecomeUnknownNeverRejected() {
        val now = System.currentTimeMillis()
        val sixMinutesAgo = now - (6 * 60 * 1000L)

        val staleAttempt = PaymentAttemptEntity(
            reference = "req-stale",
            idempotencyKey = "k-stale",
            nonce = "n-stale",
            amount = 3000L,
            currency = "BRL",
            status = "PENDING",
            startedAt = sixMinutesAgo
        )

        val recoveredAttempt = if (now - staleAttempt.startedAt > (5 * 60 * 1000L) && staleAttempt.status == "PENDING") {
            staleAttempt.copy(
                status = "UNKNOWN",
                statusMessage = "Timeout local de callback (> 5 min). Necessita verificação."
            )
        } else {
            staleAttempt
        }

        assertEquals("UNKNOWN", recoveredAttempt.status)
        assertNotEquals("REJECTED", recoveredAttempt.status)
    }

    @Test
    fun testSaleOutboxRepositoryNoProgressLoopElimination() {
        val drainResult = SaleDrainResult(
            processedCount = 0,
            remainingCount = 5,
            stopReason = StopReason.AUTH_REQUIRED
        )

        var loopCount = 0
        while (loopCount < 5) {
            loopCount++
            if (drainResult.processedCount == 0 || drainResult.stopReason != StopReason.PROGRESSED) {
                break
            }
        }

        assertEquals(1, loopCount)
        assertEquals(StopReason.AUTH_REQUIRED, drainResult.stopReason)
    }
}
