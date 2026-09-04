package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

data class CashSessionResponse(val session: CashSessionSnapshot?)

data class CashSessionSnapshot(
    @SerializedName("session_id") val sessionId: String,
    @SerializedName("operator_id") val operatorId: String? = null,
    @SerializedName("operator_name") val operatorName: String? = null,
    @SerializedName("terminal_id") val terminalId: String? = null,
    @SerializedName("opened_at") val openedAt: String,
    @SerializedName("closed_at") val closedAt: String? = null,
    val status: String,
    @SerializedName("opening_currency") val openingCurrency: String? = null,
    @SerializedName("opening_amount") val openingAmount: BigDecimal = BigDecimal.ZERO,
    @SerializedName("positions_by_currency") val positions: List<CashPosition>? = null,
    @SerializedName("cash_position_by_currency") val positionAlias: List<CashPosition>? = null,
    @SerializedName("payments_by_method_and_currency") val payments: List<CashPaymentSummary>? = null,
    @SerializedName("payments_by_method") val paymentAlias: List<CashPaymentSummary>? = null,
    val movements: List<CashMovement> = emptyList(),
    @SerializedName("closure_snapshot") val closureSnapshot: List<Map<String, Any?>> = emptyList(),
    @SerializedName("already_closed") val alreadyClosed: Boolean? = null,
    @SerializedName("closure_op_id") val closureOperationId: String? = null
) {
    fun cashPositions() = positions ?: positionAlias.orEmpty()
    fun paymentSummary() = payments ?: paymentAlias.orEmpty()
}

data class CashPosition(
    val currency: String, val opening: BigDecimal,
    @SerializedName("cash_receipts") val cashReceipts: BigDecimal,
    @SerializedName("cash_payins") val cashPayIns: BigDecimal,
    val withdrawals: BigDecimal,
    @SerializedName("cash_refunds") val cashRefunds: BigDecimal,
    @SerializedName("expected_cash") val expectedCash: BigDecimal,
    @SerializedName("counted_cash") val countedCash: BigDecimal? = null,
    val variance: BigDecimal? = null
)

data class CashPaymentSummary(
    val currency: String, @SerializedName("payment_method") val paymentMethod: String,
    @SerializedName("is_cash") val isCash: Boolean, val count: Int, val amount: BigDecimal
)

data class CashMovement(
    @SerializedName("operation_id") val operationId: String, val type: String,
    val currency: String? = null, val amount: BigDecimal, val reason: String? = null,
    @SerializedName("operator_id") val operatorId: String? = null,
    @SerializedName("operator_name") val operatorName: String? = null,
    @SerializedName("created_at") val createdAt: String
)

data class CashActionResponse(
    @SerializedName("operation_id") val operationId: String? = null,
    @SerializedName("session_id") val sessionId: String,
    val tipo: String? = null, val currency: String? = null, val amount: BigDecimal? = null,
    @SerializedName("operator_id") val operatorId: String? = null,
    @SerializedName("operator_name") val operatorName: String? = null,
    @SerializedName("terminal_id") val terminalId: String? = null,
    @SerializedName("opened_at") val openedAt: String? = null,
    @SerializedName("closed_at") val closedAt: String? = null,
    val status: String? = null,
    @SerializedName("opening_currency") val openingCurrency: String? = null,
    @SerializedName("opening_amount") val openingAmount: BigDecimal? = null,
    @SerializedName("positions_by_currency") val positions: List<CashPosition>? = null,
    @SerializedName("cash_position_by_currency") val positionAlias: List<CashPosition>? = null,
    @SerializedName("payments_by_method_and_currency") val payments: List<CashPaymentSummary>? = null,
    @SerializedName("payments_by_method") val paymentAlias: List<CashPaymentSummary>? = null,
    val movements: List<CashMovement> = emptyList(),
    @SerializedName("closure_snapshot") val closureSnapshot: List<Map<String, Any?>> = emptyList(),
    @SerializedName("already_closed") val alreadyClosed: Boolean? = null,
    @SerializedName("closure_op_id") val closureOperationId: String? = null
) {
    fun toSnapshot() = CashSessionSnapshot(sessionId, operatorId, operatorName, terminalId,
        openedAt.orEmpty(), closedAt, status ?: "OPEN", openingCurrency,
        openingAmount ?: BigDecimal.ZERO, positions, positionAlias, payments, paymentAlias, movements, closureSnapshot,
        closureOperationId = closureOperationId, alreadyClosed = alreadyClosed)
}

data class CashApiError(val error: String? = null, val code: String? = null,
    val message: String? = null, @SerializedName("message_key") val messageKey: String? = null)

/** Legacy history DTO. The current-session UI never consumes this endpoint. */
data class CashierSession(
    val id: String? = null, val tipo: String? = null, val valor: Double = 0.0,
    @SerializedName("caixa_session_id") val caixa_session_id: String? = null,
    @SerializedName("created_at") val createdAt: String? = null
)
