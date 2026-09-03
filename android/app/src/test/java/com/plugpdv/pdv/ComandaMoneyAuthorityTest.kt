package com.plugpdv.pdv

import com.plugpdv.pdv.utils.ComandaMoneyAuthority
import org.junit.Assert.assertEquals
import org.junit.Test

class ComandaMoneyAuthorityTest {
    @Test
    fun remainingBalanceIsPayableWithoutReapplyingTax() {
        val money = ComandaMoneyAuthority(72.50, 2.03, 0.0, 74.53, 24.13, 50.40)
        assertEquals(50.40, money.payableAmount(), 0.0001)
    }

    @Test
    fun pygValuesRemainInTransactionCurrency() {
        val money = ComandaMoneyAuthority(100000.0, 10000.0, 0.0, 110000.0, 0.0, 110000.0)
        assertEquals(110000.0, money.payableAmount(), 0.0001)
    }
}
