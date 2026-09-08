package com.plugpdv.pdv

import com.plugpdv.pdv.utils.DirectSalePickupCode
import org.junit.Assert.*
import org.junit.Test

class DirectSaleTicketLayoutTest {
    @Test fun pickupCodeIsDeterministicAndCopySpecific() {
        val a = DirectSalePickupCode.forTicket("sale-123", "product-456", 1)
        assertEquals(a, DirectSalePickupCode.forTicket("sale-123", "product-456", 1))
        assertNotEquals(a, DirectSalePickupCode.forTicket("sale-123", "product-456", 2))
        assertTrue(a.matches(Regex("[0-9A-F]{4}-[0-9A-F]{4}")))
    }

    @Test fun pickupCodeUsesUtf8AndDoesNotUseStringHashCode() {
        val accented = DirectSalePickupCode.forTicket("venda-ç", "Coca zéro - lata", 1)
        assertEquals(9, accented.length)
        assertNotEquals(DirectSalePickupCode.forTicket("venda-ç", "Coca zéro - lata", 1), DirectSalePickupCode.forTicket("venda-c", "Coca zéro - lata", 1))
    }
}
