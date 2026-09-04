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

    @Test
    fun authoritativeUnitPriceWinsOverNestedCatalogBasePrice() {
        val json = gson.toJson(listOf(
            MesaItemDto(
                id = "hot-dog",
                produto_id = "p1",
                preco_unitario = 20_880.0,
                quantidade = 1,
                nestedProduct = Product(id = "p1", selling_price = 18.0, price_currency = "BRL")
            )
        ))

        val item = ComandaItemHydrator.fromSnapshot(json, "PYG").single()

        assertEquals(20_880.0, item.product.selling_price!!, 0.0)
        assertEquals("PYG", item.product.price_currency)
    }

    @Test
    fun authoritativeSubtotalProvidesUnitPriceWhenUnitPriceIsAbsent() {
        val json = gson.toJson(listOf(
            MesaItemDto(id = "hot-dog", produto_id = "p1", preco_unitario = null, quantidade = 1, subtotal = 20_880.0)
        ))

        val item = ComandaItemHydrator.fromSnapshot(json, "PYG").single()

        assertEquals(20_880.0, item.product.selling_price!!, 0.0)
        assertEquals("PYG", item.product.price_currency)
    }

    @Test
    fun nestedCatalogBasePriceIsNeverReinterpretedAsComandaCurrency() {
        val json = gson.toJson(listOf(
            MesaItemDto(
                id = "hot-dog",
                produto_id = "p1",
                preco_unitario = null,
                quantidade = 1,
                subtotal = null,
                nestedProduct = Product(id = "p1", selling_price = 18.0, price_currency = "BRL")
            )
        ))

        val item = ComandaItemHydrator.fromSnapshot(json, "PYG").single()

        assertEquals(null, item.product.selling_price)
        assertEquals("PYG", item.product.price_currency)
    }

    @Test
    fun preservesThreeAuthoritativePygItemPrices() {
        val json = gson.toJson(listOf(
            MesaItemDto(id = "1", produto_id = "p1", preco_unitario = 20_880.0, quantidade = 1),
            MesaItemDto(id = "2", produto_id = "p2", preco_unitario = 6_960.0, quantidade = 1),
            MesaItemDto(id = "3", produto_id = "p3", preco_unitario = 11_600.0, quantidade = 1)
        ))

        val items = ComandaItemHydrator.fromSnapshot(json, "PYG")

        assertEquals(listOf(20_880.0, 6_960.0, 11_600.0), items.map { it.product.selling_price })
        assertTrue(items.all { it.product.price_currency == "PYG" })
    }
}
