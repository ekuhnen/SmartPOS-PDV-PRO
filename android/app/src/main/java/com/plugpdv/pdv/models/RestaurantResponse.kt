package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class RestaurantResponse(
    val setores: List<Sector>? = emptyList(),
    val total_mesas: Int? = 0
)

data class Sector(
    val id: String? = "",
    val nome: String? = "",
    val mesas: List<MesaDto>? = emptyList()
)

data class MesaDto(
    val id: String? = null,
    val numero: Int = 0,
    val status: String? = null,
    val comanda_id: String? = null,
    @SerializedName("nome_cliente") val nome_cliente: String? = null,
    val pessoas_qtd: Int? = 0,
    val itens: List<MesaItemDto>? = null
)

data class MesaItemDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName(value = "produto_id", alternate = ["product_id", "id_produto"])
    val produto_id: String? = null,
    @SerializedName(value = "nome", alternate = ["product_name", "name", "produto_nome", "nome_produto", "nome_snapshot"])
    val nome: String? = null,
    @SerializedName(value = "preco_unitario", alternate = ["unit_price", "price", "preco", "valor", "valor_unitario", "preco_unit_snapshot", "preco_unitario_snapshot"])
    val preco_unitario: Double? = 0.0,
    @SerializedName(value = "quantidade", alternate = ["qtd", "amount", "quantity"])
    val quantidade: Int? = 0,
    @SerializedName(value = "subtotal", alternate = ["total", "valor_total"])
    val subtotal: Double? = 0.0,
    @SerializedName(value = "observacao", alternate = ["observacao_item", "obs"])
    val observacao: String? = null,
    @SerializedName("status") val status: String? = null,
    @SerializedName("produto") val nestedProduct: Product? = null
)
