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
    @SerializedName(value = "itens", alternate = ["items"]) val itens: List<MesaItemDto> = emptyList(),
    @SerializedName(value = "pagamentos", alternate = ["payments"]) val pagamentos: List<ComandaPaymentDto> = emptyList()
)

data class ComandaPaymentDto(
    val id: String,
    val forma: String,
    val valor: Double,
    @SerializedName("data_pagamento") val dataPagamento: String?
)
