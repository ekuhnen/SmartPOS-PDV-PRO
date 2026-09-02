package com.plugpdv.pdv.ui.sale

import com.plugpdv.pdv.models.Product
import com.plugpdv.pdv.models.TableItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CheckoutPrintModelTest {
    private fun item(name: String, price: Double) = TableItem(product = Product(name = name, selling_price = price, price_currency = "BRL"))

    @Test
    fun itemsModePrintProjectionUsesSelectedChargeNotComandaTotal() {
        val state = CheckoutUiState(splitMode = 2, currentToPay = 18.0, taxAmount = 0.50, finalToPay = 18.50)
        val model = CheckoutPrintModelFactory.from(state, 1, "BRL", listOf(CheckoutPrintLine(item("Cachorro Quente", 18.0), 1)), 116.0, 0.0, 116.0)

        assertEquals(1, model.lines.size)
        assertEquals(1, model.lines.single().selectedQuantity)
        assertEquals(18.0, model.subtotal, 0.001)
        assertEquals(0.50, model.tax, 0.001)
        assertEquals(18.50, model.finalToPay, 0.001)
        assertTrue(model.finalToPay != model.comandaTotal)
    }

    @Test
    fun totalProjectionKeepsFullChargeAndFinalTaxedAmount() {
        val state = CheckoutUiState(splitMode = 0, currentToPay = 116.0, taxAmount = 3.25, finalToPay = 119.25)
        val model = CheckoutPrintModelFactory.from(
            state, 1, "BRL",
            listOf(CheckoutPrintLine(item("Cachorro Quente", 18.0), 1), CheckoutPrintLine(item("Vinho", 98.0), 1)),
            116.0, 0.0, 116.0
        )
        assertEquals(119.25, model.finalToPay, 0.001)
        assertEquals(2, model.lines.size)
    }
}
