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
    @SerializedName("base_currency") val baseCurrency: String? = null,
    @SerializedName("requires_reconciliation") val requiresReconciliation: Boolean = false,
    @SerializedName(value = "itens", alternate = ["items"]) val itens: List<MesaItemDto> = emptyList(),
    @SerializedName(value = "pagamentos", alternate = ["payments"]) val pagamentos: List<ComandaPaymentDto> = emptyList()
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
)
