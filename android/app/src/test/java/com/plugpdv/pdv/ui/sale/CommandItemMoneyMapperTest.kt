package com.plugpdv.pdv.ui.sale

import com.plugpdv.pdv.models.MesaItemDto
import com.plugpdv.pdv.models.Product
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandItemMoneyMapperTest {
    @Test
    fun authoritativeUnitPriceWinsOverNestedCatalogBasePrice() {
        val item = MesaItemDto(
            produto_id = "product-1",
            preco_unitario = 20_880.0,
            quantidade = 1,
            subtotal = 20_880.0,
            nestedProduct = Product(
                id = "product-1",
                selling_price = 18.0,
                price_currency = "BRL"
            )
        )

        val money = CommandItemMoneyMapper.fromAuthoritativeDetail(item, quantity = 1, baseCurrency = "PYG")

        assertEquals(20_880.0, money.price!!, 0.0)
        assertEquals("PYG", money.currency)
    }

    @Test
    fun authoritativeSubtotalProvidesUnitPriceWhenUnitPriceIsAbsent() {
        val item = MesaItemDto(
            produto_id = "product-1",
            preco_unitario = null,
            quantidade = 1,
            subtotal = 20_880.0
        )

        val money = CommandItemMoneyMapper.fromAuthoritativeDetail(item, quantity = 1, baseCurrency = "pyg")

        assertEquals(20_880.0, money.price!!, 0.0)
        assertEquals("PYG", money.currency)
    }

    @Test
    fun nestedCatalogBasePriceIsNotUsedWhenAuthoritativeMoneyIsUnavailable() {
        val item = MesaItemDto(
            produto_id = "product-1",
            preco_unitario = null,
            quantidade = 1,
            subtotal = null,
            nestedProduct = Product(
                id = "product-1",
                selling_price = 18.0,
                price_currency = "BRL"
            )
        )

        val money = CommandItemMoneyMapper.fromAuthoritativeDetail(item, quantity = 1, baseCurrency = "PYG")

        assertNull(money.price)
        assertEquals("PYG", money.currency)
    }
}
