package com.plugpdv.pdv

import com.google.gson.Gson
import com.plugpdv.pdv.models.ComandaItemPaymentStateDto
import com.plugpdv.pdv.models.ComandaItemAllocation
import com.plugpdv.pdv.models.CommandCheckoutCommitRequest
import com.plugpdv.pdv.models.Product
import com.plugpdv.pdv.models.TableItem
import com.plugpdv.pdv.utils.ComandaPaymentStateAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PayByItemsAdapterRegressionTest {
    private fun item(id: String, quantity: Int) = TableItem(
        id = id,
        product = Product(id = "product-$id", name = id, selling_price = 10.0),
        quantity = quantity
    )

    @Test
    fun stateOverlayNeverReplacesBaseItemsAndUsesRemainingQuantity() {
        val base = listOf(item("a", 2), item("b", 1))
        val overlaid = ComandaPaymentStateAdapter.apply(
            base,
            listOf(
                ComandaItemPaymentStateDto(comandaItemId = "a", originalQuantity = 2, paidQuantity = 1, remainingQuantity = 1),
                ComandaItemPaymentStateDto(comandaItemId = "b", originalQuantity = 1, paidQuantity = 1, remainingQuantity = 0)
            )
        )
        assertEquals(2, overlaid.size)
        assertEquals(1, overlaid[0].quantity - overlaid[0].paidQuantity)
        assertEquals(0, overlaid[1].quantity - overlaid[1].paidQuantity)
    }

    @Test
    fun missingStateLeavesFreshUnpaidItemPayable() {
        val overlaid = ComandaPaymentStateAdapter.apply(listOf(item("a", 2)), emptyList())
        assertEquals(1, overlaid.size)
        assertEquals(2, overlaid.single().quantity - overlaid.single().paidQuantity)
    }

    @Test
    fun itemsPayloadIsExplicitAndStableForRetry() {
        val request = CommandCheckoutCommitRequest(
            comandaId = "c1", moeda = "PYG",
            items = listOf(ComandaItemAllocation("a", 1))
        )
        val json = Gson().toJson(request)
        val retry = Gson().fromJson(json, CommandCheckoutCommitRequest::class.java)
        assertTrue(json.contains("\"items\""))
        assertEquals(request.items, retry.items)
        assertEquals("a", retry.items!!.single().comandaItemId)
        assertEquals(1, retry.items!!.single().quantity)
    }
}
