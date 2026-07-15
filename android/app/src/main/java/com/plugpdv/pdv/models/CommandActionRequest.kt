package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName

data class CommandActionRequest(
    var action: String = "",
    @SerializedName("comanda_id") var comandaId: String? = null,
    @SerializedName("id") var id: String? = null,
    @SerializedName("mesa_id") var mesaId: String? = null,
    @SerializedName("customerName") var customerName: String? = null,
    @SerializedName("item_id") var order_id: String? = null,
    @SerializedName("produto_id") var product_id: String? = null,
    @SerializedName("qtd") var quantity: Int? = null,
    @SerializedName("observacao") var observation: String? = null,
    @SerializedName("observacao_item") var itemObservation: String? = null,
    @SerializedName("motivo") var reason: String? = null,
    @SerializedName("garcom_id") var waiterId: String? = null,
    @SerializedName("status") var status: String? = null,
    @SerializedName("item_ids") var itemIds: List<String>? = null,
    
    // Abrir fields
    @SerializedName("pessoas_qtd") var people_count: Int? = null,
    @SerializedName("numero") var numero: Int? = null,
    @SerializedName("nome_cliente") var nome_cliente: String? = null,
    
    // Pagamento fields
    @SerializedName("forma") var paymentForm: String? = null,
    @SerializedName("valor") var amount: Double? = null,
    @SerializedName("moeda") var currency: String? = null,
    @SerializedName("referencia_externa") var externalRef: String? = null,
    
    // Transfer fields
    @SerializedName("mesa_destino_id") var destinationTableId: String? = null
)
