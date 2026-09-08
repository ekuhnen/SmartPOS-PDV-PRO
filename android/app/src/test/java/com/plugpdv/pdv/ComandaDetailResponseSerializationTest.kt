package com.plugpdv.pdv

import com.google.gson.Gson
import com.plugpdv.pdv.models.ComandaDetailResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class ComandaDetailResponseSerializationTest {
    @Test
    fun detailDisplayNameMapsToCustomerName() {
        val detail = Gson().fromJson(
            """{"id":"comanda-name","status":"EM_CONSUMO","display_name":"Evandro teste 2","total_comanda":0}""",
            ComandaDetailResponse::class.java
        )
        assertEquals("Evandro teste 2", detail.nomeCliente)
    }

    @Test
    fun deployedDetailShapeMapsAuthoritativePygMoney() {
        val json = """
            {
              "id": "comanda-1",
              "mesa_id": "mesa-1",
              "status": "ABERTA",
              "subtotal": 39440,
              "tax_amount": 3944,
              "service_fee": 0,
              "total_comanda": 43384,
              "base_currency": "PYG",
              "itens": [{
                "id": "item-1",
                "produto_id": "product-1",
                "preco_unitario": 20880,
                "quantidade": 1,
                "subtotal": 20880,
                "produto": {"id": "product-1", "selling_price": 18, "price_currency": "BRL"}
              }]
            }
        """.trimIndent()

        val detail = Gson().fromJson(json, ComandaDetailResponse::class.java)

        assertEquals("PYG", detail.baseCurrency)
        assertEquals(39_440.0, detail.subtotal!!, 0.0)
        assertEquals(3_944.0, detail.taxAmount!!, 0.0)
        assertEquals(0.0, detail.serviceFee!!, 0.0)
        assertEquals(43_384.0, detail.total, 0.0)
        assertEquals(20_880.0, detail.itens.single().preco_unitario!!, 0.0)
        assertEquals(18.0, detail.itens.single().nestedProduct!!.selling_price!!, 0.0)
    }
}
