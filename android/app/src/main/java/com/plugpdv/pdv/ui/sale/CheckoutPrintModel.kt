package com.plugpdv.pdv.ui.sale

import com.plugpdv.pdv.models.TableItem

/** Immutable projection of the already-calculated checkout state for pre-payment printing. */
data class CheckoutPrintLine(
    val item: TableItem,
    val selectedQuantity: Int,
    val unitDisplay: String = "",
    val subtotalDisplay: String = ""
)

data class CheckoutPrintModel(
    val mode: Int,
    val tableNumber: Int,
    val transactionCurrency: String,
    val lines: List<CheckoutPrintLine>,
    val subtotal: Double,
    val tax: Double,
    val serviceFee: Double,
    val finalToPay: Double,
    val comandaTotal: Double,
    val paid: Double,
    val balance: Double
)

object CheckoutPrintModelFactory {
    fun from(
        state: CheckoutUiState,
        tableNumber: Int,
        transactionCurrency: String,
        lines: List<CheckoutPrintLine>,
        comandaTotal: Double,
        paid: Double,
        balance: Double
    ): CheckoutPrintModel = CheckoutPrintModel(
        mode = state.splitMode,
        tableNumber = tableNumber,
        transactionCurrency = transactionCurrency,
        lines = lines,
        subtotal = state.currentToPay,
        tax = state.taxAmount,
        serviceFee = state.serviceFeeAmount,
        finalToPay = state.finalToPay,
        comandaTotal = comandaTotal,
        paid = paid,
        balance = balance
    )
}
