package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class RestaurantResponse(
    val setores: List<Sector>? = emptyList()
)

data class Sector(
    val id: String,
    val nome: String,
    val mesas: List<MesaDto>? = emptyList()
)

data class MesaDto(
    val id: String,
    val numero: Int,
    val status: String?,
    val comanda_id: String?,
    @SerializedName("nome_cliente") val nome_cliente: String?,
    val pessoas_qtd: Int,
    val itens: List<MesaItemDto>? = null
)

data class MesaItemDto(
    @SerializedName("id") val id: String,
    @SerializedName(value = "produto_id", alternate = ["product_id", "id_produto"])
    val produto_id: String?,
    @SerializedName(value = "nome", alternate = ["product_name", "name", "produto_nome", "nome_produto"])
    val nome: String?,
    @SerializedName(value = "preco_unitario", alternate = ["unit_price", "price", "preco", "valor", "valor_unitario"])
    val preco_unitario: Double = 0.0,
    @SerializedName(value = "quantidade", alternate = ["qtd", "amount", "quantity"])
    val quantidade: Int = 0,
    @SerializedName("observacao") val observacao: String?,
    @SerializedName("status") val status: String?,
    @SerializedName("produto") val nestedProduct: Product? = null
)
