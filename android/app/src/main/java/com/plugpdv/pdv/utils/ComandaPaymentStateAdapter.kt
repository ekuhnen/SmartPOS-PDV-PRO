package com.plugpdv.pdv.utils

import com.plugpdv.pdv.models.ComandaItemPaymentStateDto
import com.plugpdv.pdv.models.TableItem

/** Overlays server payment allocation state without replacing the base Mesa items. */
object ComandaPaymentStateAdapter {
    fun apply(baseItems: List<TableItem>, states: List<ComandaItemPaymentStateDto>): List<TableItem> {
        val byId = states.mapNotNull { state -> state.comandaItemId?.let { it to state } }.toMap()
        return baseItems.map { item ->
            val state = item.id?.let(byId::get)
            if (state == null) item.copy(paidQuantity = 0, isPaid = false)
            else {
                val paid = state.paidQuantity
                    ?: state.originalQuantity?.let { original ->
                        (original - (state.remainingQuantity ?: original)).coerceAtLeast(0)
                    }
                    ?: 0
                item.copy(
                    paidQuantity = paid.coerceIn(0, item.quantity),
                    isPaid = state.remainingQuantity == 0 || paid >= item.quantity
                )
            }
        }
    }
}
