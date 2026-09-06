package com.plugpdv.pdv

import com.google.gson.Gson
import com.plugpdv.pdv.models.CommandActionRequest
import com.plugpdv.pdv.models.SetComandaServiceFeeResponse
import com.plugpdv.pdv.utils.MoneyDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class ServiceFeeAuthoritySerializationTest {
    private val gson = Gson()

    @Test
    fun percentageRequestSendsIntentAndCasOnly() {
        val json = gson.toJson(CommandActionRequest(
            action = "set_service_fee",
            comandaId = "c-1",
            mode = "percentage",
            percentage = BigDecimal("10"),
            comandaVersion = 7L
        ))

        assertTrue(json.contains("\"action\":\"set_service_fee\""))
        assertTrue(json.contains("\"mode\":\"percentage\""))
        assertTrue(json.contains("\"percentage\":10"))
        assertTrue(json.contains("\"expected_comanda_version\":7"))
        assertFalse(json.contains("\"amount\""))
    }

    @Test
    fun fixedAmountUsesMajorUnitCurrencyScale() {
        val pygWire = MoneyDecimal.toProtocolAmount(BigDecimal("15000"), "PYG")
        val brlWire = MoneyDecimal.toProtocolAmount(BigDecimal("15.00"), "BRL")
        assertEquals("15000", pygWire)
        assertEquals("15.00", brlWire)

        val request = CommandActionRequest(
            action = "set_service_fee",
            comandaId = "c-1",
            mode = "amount",
            serviceFeeAmount = BigDecimal(brlWire),
            serviceFeeCurrency = "BRL",
            comandaVersion = 3L
        )
        val json = gson.toJson(request)
        assertTrue(json.contains("\"amount\":15.00"))
        assertTrue(json.contains("\"currency\":\"BRL\""))
    }

    @Test
    fun authoritativeResponseIsReadWithoutLocalTotalFormula() {
        val response = gson.fromJson(
            """{"ok":true,"comanda_id":"c-1","currency":"PYG","service_fee":11194,"total_liquido":134328,"saldo_base":134328,"versao":8}""",
            SetComandaServiceFeeResponse::class.java
        )
        assertEquals(11194.0, response.serviceFee, 0.0)
        assertEquals(134328.0, response.totalLiquido!!, 0.0)
        assertEquals(8L, response.versao)
    }
}
