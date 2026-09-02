package com.plugpdv.pdv.utils

import com.google.gson.Gson
import com.plugpdv.pdv.models.MesaItemDto
import com.plugpdv.pdv.models.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComandaItemHydratorTest {
    private val gson = Gson()

    @Test
    fun mapsActiveItemsAndPreservesIdentityAndCurrency() {
        val json = gson.toJson(listOf(
            MesaItemDto(id = "100", produto_id = "p", nome = "Coca", preco_unitario = 10.0, quantidade = 3, status = "ATIVO", paidQuantity = 1),
            MesaItemDto(id = "145", produto_id = "p", nome = "Coca", preco_unitario = 10.0, quantidade = 1, status = "ATIVO"),
            MesaItemDto(id = "cancelled", produto_id = "x", nome = "Old", preco_unitario = 5.0, quantidade = 1, status = "CANCELADO")
        ))

        val items = ComandaItemHydrator.fromSnapshot(json, "BRL")

        assertEquals(3, items.size)
        assertEquals(listOf("100", "145", "cancelled"), items.map { it.id })
        assertEquals(3, items[0].quantity)
        assertEquals(1, items[0].paidQuantity)
        assertEquals("BRL", items[0].product.price_currency)
        assertEquals(10.0, items[0].product.selling_price!!, 0.0)
        assertFalse(items[0].removed)
        assertTrue(items[2].removed)
    }

    @Test
    fun missingSnapshotIsEmptyButNeverInvented() {
        assertTrue(ComandaItemHydrator.fromSnapshot("[]", "BRL").isEmpty())
        assertTrue(ComandaItemHydrator.fromSnapshot(null, "BRL").isEmpty())
    }
}
