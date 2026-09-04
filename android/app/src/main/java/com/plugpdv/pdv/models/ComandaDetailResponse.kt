package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class ComandaDetailResponse(
    val id: String,
    @SerializedName("mesa_id") val mesaId: String?,
    @SerializedName("status") val status: String,
    @SerializedName("numero") val numero: Int? = null,
    @SerializedName("nome_cliente") val nomeCliente: String? = null,
    @SerializedName(value = "total_comanda", alternate = ["total"]) val total: Double,
    @SerializedName(value = "total_pago", alternate = ["total_paid", "paid_amount"]) val totalPago: Double = 0.0,
    @SerializedName("total_pago_base") val totalPagoBase: Double? = null,
    @SerializedName("saldo_base") val saldoBase: Double? = null,
    @SerializedName("subtotal") val subtotal: Double? = null,
    @SerializedName("tax_amount") val taxAmount: Double? = null,
    @SerializedName("tax_snapshot") val taxSnapshot: List<TaxSnapshotDto>? = null,
    @SerializedName("service_fee") val serviceFee: Double? = null,
    @SerializedName("base_currency") val baseCurrency: String? = null,
    @SerializedName("requires_reconciliation") val requiresReconciliation: Boolean = false,
    @SerializedName(value = "itens", alternate = ["items"]) val itens: List<MesaItemDto> = emptyList(),
    @SerializedName(value = "pagamentos", alternate = ["payments"]) val pagamentos: List<ComandaPaymentDto> = emptyList()
)

/** Descriptive tax metadata; tax_amount remains the monetary authority. */
data class TaxSnapshotDto(
    @SerializedName(value = "name", alternate = ["tax_name"]) val name: String? = null,
    @SerializedName(value = "rate", alternate = ["percentage"]) val rate: Double? = null,
    @SerializedName("code") val code: String? = null
)

/** Deliberately separate from ComandaDetailResponse: this endpoint augments checkout
 * item payment state and must never replace the normal Mesa/comanda read model. */
data class ComandaPaymentStateResponse(
    @SerializedName("itens_payment_state") val itensPaymentState: List<ComandaItemPaymentStateDto> = emptyList(),
    @SerializedName(value = "pagamentos", alternate = ["payments"]) val pagamentos: List<ComandaPaymentDto> = emptyList()
)

data class ComandaItemPaymentStateDto(
    @SerializedName(value = "comanda_item_id", alternate = ["item_id", "id"]) val comandaItemId: String? = null,
    @SerializedName(value = "original_quantity", alternate = ["quantity", "quantidade"]) val originalQuantity: Int? = null,
    @SerializedName(value = "paid_quantity", alternate = ["paidQuantity", "quantidade_paga"]) val paidQuantity: Int? = null,
    @SerializedName(value = "remaining_quantity", alternate = ["remainingQuantity", "quantidade_restante"]) val remainingQuantity: Int? = null
)

data class ComandaPaymentAllocationDto(
    @SerializedName(value = "comanda_item_id", alternate = ["item_id"]) val comandaItemId: String? = null,
    @SerializedName(value = "name", alternate = ["item_name", "nome"]) val name: String? = null,
    val quantity: Int = 0,
    @SerializedName(value = "line_amount", alternate = ["amount", "valor"]) val lineAmount: Double? = null,
    val currency: String? = null
)

data class ComandaPaymentDto(
    val id: String,
    val forma: String,
    val valor: Double,
    val moeda: String = "BRL",
    @SerializedName("valor_base") val valorBase: Double? = null,
    @SerializedName("base_currency") val baseCurrency: String? = null,
    @SerializedName("fx_rate") val fxRate: Double? = null,
    @SerializedName("data_pagamento") val dataPagamento: String? = null
    ,@SerializedName("allocations") val allocations: List<ComandaPaymentAllocationDto> = emptyList()
    ,@SerializedName("allocation_mode") val allocationMode: String? = null
)
