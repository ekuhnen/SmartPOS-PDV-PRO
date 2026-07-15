package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class ComandasListResponse(
    val total: Int = 0,
    val comandas: List<ComandaListItem> = emptyList()
) {
    data class ComandaListItem(
        val id: String,
        @SerializedName("mesa_id") val mesaId: String?,
        val status: String,
        val numero: Int?,
        @SerializedName("nome_cliente") val nomeCliente: String?,
        val observacao: String?
    )
}
