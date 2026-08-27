package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class CommandCheckoutCommitRequest(
    @SerializedName("action") val action: String = "checkout_commit",
    @SerializedName("comanda_id") val comandaId: String,
    @SerializedName("mesa_id") val mesaId: String? = null,
    @SerializedName("forma") val forma: String = "DINHEIRO",
    @SerializedName("valor") val valor: Double = 0.0,
    @SerializedName("moeda") val moeda: String = "BRL",
    @SerializedName("referencia_externa") val referenciaExterna: String? = null,
    @SerializedName("should_register_sale") val shouldRegisterSale: Boolean = false,
    @SerializedName("sale_items") val saleItems: List<SaleItem>? = null,
    @SerializedName("discount") val discount: Double = 0.0,
    @SerializedName("service_fee") val serviceFee: Double = 0.0,
    @SerializedName("service_fee_kind") val serviceFeeKind: String? = null,
    @SerializedName("valor_base") val valorBase: Double? = null
)

data class ComandaCheckoutCommitResponse(
    @SerializedName("success") val success: Boolean = true,
    @SerializedName("created_new") val createdNew: Boolean = true,
    @SerializedName("payment_id") val paymentId: String? = null,
    @SerializedName("sale_id") val saleId: String? = null,
    @SerializedName("comanda_id") val comandaId: String? = null,
    @SerializedName("comanda_status") val comandaStatus: String? = null,
    @SerializedName("mesa_id") val mesaId: String? = null,
    @SerializedName("mesa_status") val mesaStatus: String? = null,
    @SerializedName("total_paid") val totalPaid: Double = 0.0,
    @SerializedName("remaining_balance") val remainingBalance: Double = 0.0,
    @SerializedName("closed") val closed: Boolean = false,
    @SerializedName("requires_reconciliation") val requiresReconciliation: Boolean = false
)
