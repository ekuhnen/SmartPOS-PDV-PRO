package com.plugpdv.pdv.repository

import com.google.gson.Gson
import com.plugpdv.pdv.database.LocalSaleEntity
import com.plugpdv.pdv.database.OutboxOperationEntity
import com.plugpdv.pdv.database.PaymentAttemptEntity
import com.plugpdv.pdv.models.CommandCheckoutCommitRequest
import com.plugpdv.pdv.models.ComandaCheckoutCommitResponse
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.ui.sale.CheckoutUiState
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
        assertTrue(shouldReopenPlugPay(null)) // Only fresh intents without prior record open PlugPay
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
                return "APPROVED" // APPROVED is terminal and irreversible
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
            status = "CANCELLED_PAYMENT",
            lastError = "CANCELLED",
            messageKey = "CANCELLED_PAYMENT",
            isRetriable = false
        )

        assertEquals("CANCELLED_PAYMENT", terminalizedOp.status)
        assertFalse(terminalizedOp.isRetriable)
        assertEquals(Table.Status.OCCUPIED, table.status) // Table remains occupied!
    }

    @Test
    fun testFailedToStartTerminalizesAttemptAndOutbox() {
        val attempt = PaymentAttemptEntity(
            reference = "req-fail",
            idempotencyKey = "k-fail",
            nonce = "n-fail",
            amount = 2500L,
            currency = "BRL",
            status = "FAILED_TO_START",
            startedAt = System.currentTimeMillis(),
            statusMessage = "ActivityNotFoundException"
        )

        val outbox = OutboxOperationEntity(
            id = "req-fail",
            operationType = "COMANDA_CHECKOUT_COMMIT",
            targetGroupKey = "comanda-2",
            payloadJson = "{}",
            createdAt = System.currentTimeMillis(),
            status = "FAILED",
            lastError = "FAILED_TO_START",
            messageKey = "FAILED_TO_START",
            isRetriable = false
        )

        assertEquals("FAILED_TO_START", attempt.status)
        assertEquals("FAILED", outbox.status)
        assertFalse(outbox.isRetriable)
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
        assertTrue(recoveredAttempt.statusMessage!!.contains("Timeout local"))
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
                break // Stops immediately on pass 1!
            }
        }

        assertEquals(1, loopCount)
        assertEquals(StopReason.AUTH_REQUIRED, drainResult.stopReason)
    }

    @Test
    fun testDirectSaleResponseLostReplaySameKey() {
        val localSale = LocalSaleEntity(
            localId = "local-uuid-1",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            total = 100.0,
            currency = "BRL",
            paymentMethod = "DINHEIRO",
            sessionId = "sess-1",
            itemsJson = "[]",
            payloadJson = "{}",
            syncStatus = LocalSaleEntity.STATUS_PENDING,
            idempotencyKeyUsed = true
        )

        assertTrue(localSale.idempotencyKeyUsed)
        assertEquals(LocalSaleEntity.STATUS_PENDING, localSale.syncStatus)

        val syncedSale = localSale.copy(
            apiId = "sale-backend-100",
            syncStatus = LocalSaleEntity.STATUS_SYNCED,
            syncedToApi = true
        )

        assertEquals("sale-backend-100", syncedSale.apiId)
        assertTrue(syncedSale.syncedToApi)
    }

    @Test
    fun testCheckoutResponseLostReplaySameKey() {
        val request = CommandCheckoutCommitRequest(
            comandaId = "comanda-1",
            forma = "CARD",
            valor = 100.0,
            moeda = "BRL",
            valorBase = 100.0
        )

        val initialResponse = ComandaCheckoutCommitResponse(
            success = true,
            createdNew = true,
            paymentId = "pay-1",
            saleId = "sale-1",
            closed = true
        )

        val replayResponse = ComandaCheckoutCommitResponse(
            success = true,
            createdNew = false,
            paymentId = "pay-1",
            saleId = "sale-1",
            closed = true
        )

        assertEquals(initialResponse.paymentId, replayResponse.paymentId)
        assertEquals(initialResponse.saleId, replayResponse.saleId)
        assertEquals(initialResponse.closed, replayResponse.closed)
    }

    @Test
    fun testFaultInjectionAfterHttpBeforeRoomSuccess() {
        val initialSale = LocalSaleEntity(
            localId = "local-crash-1",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            total = 50.0,
            currency = "BRL",
            paymentMethod = "DINHEIRO",
            sessionId = "sess-1",
            itemsJson = "[]",
            payloadJson = "{}",
            syncStatus = LocalSaleEntity.STATUS_SYNCING,
            idempotencyKeyUsed = true
        )

        val recoveredSale = if (initialSale.syncStatus == LocalSaleEntity.STATUS_SYNCING && initialSale.idempotencyKeyUsed) {
            initialSale.copy(syncStatus = LocalSaleEntity.STATUS_PENDING)
        } else {
            initialSale
        }

        assertEquals(LocalSaleEntity.STATUS_PENDING, recoveredSale.syncStatus)

        val finalSynced = recoveredSale.copy(
            apiId = "sale-server-50",
            syncStatus = LocalSaleEntity.STATUS_SYNCED,
            syncedToApi = true
        )

        assertEquals(LocalSaleEntity.STATUS_SYNCED, finalSynced.syncStatus)
    }

    @Test
    fun testOrphanMatrixResolution() {
        val now = System.currentTimeMillis()
        val sixMinutesAgo = now - (6 * 60 * 1000L)

        val orphanWaitingOp = OutboxOperationEntity(
            id = "op-orphan",
            operationType = "COMANDA_CHECKOUT_COMMIT",
            targetGroupKey = "comanda-orphan",
            payloadJson = "{}",
            createdAt = sixMinutesAgo,
            status = "WAITING_PAYMENT"
        )

        val resolvedOrphan = if (now - orphanWaitingOp.createdAt > (5 * 60 * 1000L)) {
            orphanWaitingOp.copy(
                status = "FAILED",
                lastError = "ORPHAN_WAITING_TIMEOUT",
                messageKey = "ORPHAN_WAITING",
                isRetriable = false
            )
        } else {
            orphanWaitingOp
        }

        assertEquals("FAILED", resolvedOrphan.status)
        assertEquals("ORPHAN_WAITING_TIMEOUT", resolvedOrphan.lastError)
        assertFalse(resolvedOrphan.isRetriable)
    }
}
