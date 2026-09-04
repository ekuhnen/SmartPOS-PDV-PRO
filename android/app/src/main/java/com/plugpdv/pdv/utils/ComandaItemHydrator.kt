package com.plugpdv.pdv.utils

import com.google.gson.Gson
import com.plugpdv.pdv.models.MesaItemDto
import com.plugpdv.pdv.models.Product
import com.plugpdv.pdv.models.TableItem

/** Reconstructs checkout items from the authoritative comanda snapshot payload. */
object ComandaItemHydrator {
    fun authoritativeUnitPrice(dto: MesaItemDto, quantity: Int): Double? = when {
        dto.preco_unitario != null && dto.preco_unitario != 0.0 -> dto.preco_unitario
        dto.subtotal != null && dto.subtotal != 0.0 && quantity > 0 -> dto.subtotal / quantity
        else -> null
    }

    fun fromSnapshot(itemsJson: String?, baseCurrency: String?): MutableList<TableItem> {
        if (itemsJson.isNullOrBlank() || baseCurrency.isNullOrBlank()) return mutableListOf()
        val items: List<MesaItemDto> = runCatching {
            Gson().fromJson(itemsJson, Array<MesaItemDto>::class.java)?.toList().orEmpty()
        }.getOrDefault(emptyList())

        return items.mapNotNull { dto ->
            val productId = dto.nestedProduct?.id ?: dto.produto_id ?: return@mapNotNull null
            val quantity = (dto.quantidade ?: 0).coerceAtLeast(0)
            val unitPrice = authoritativeUnitPrice(dto, quantity)
            val paidQuantity = (dto.paidQuantity ?: if (dto.paid == true) quantity else 0)
                .coerceIn(0, quantity)
            val removed = dto.status.equals("CANCELADO", true) || dto.status.equals("REMOVIDO", true)
            TableItem(
                id = dto.id,
                product = Product(
                    id = productId,
                    name = dto.nestedProduct?.name ?: dto.nome,
                    selling_price = unitPrice,
                    price_currency = baseCurrency
                ),
                quantity = quantity,
                paidQuantity = paidQuantity,
                isPaid = dto.paid == true || paidQuantity >= quantity,
                observation = dto.observacao,
                status = dto.status ?: "PENDING",
                removed = removed,
                serverIds = dto.id?.let { mutableListOf(it) }
            )
        }.toMutableList()
    }
}
