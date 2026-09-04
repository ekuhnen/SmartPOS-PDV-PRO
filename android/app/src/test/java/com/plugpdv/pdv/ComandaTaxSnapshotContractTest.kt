package com.plugpdv.pdv

import com.google.gson.Gson
import com.plugpdv.pdv.models.ComandaDetailResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ComandaTaxSnapshotContractTest {
    @Test
    fun deployedArrayTaxSnapshotParsesAuthoritativeMoney() {
        val json = """
            {
              "id":"c1", "status":"ABERTA", "total":74.53,
              "subtotal":72.50, "tax_amount":2.03,
              "tax_snapshot":{"name":"ICMS","rate":2.8},
              "total_pago_base":24.13, "saldo_base":50.40,
              "itens":[]
            }
        """.trimIndent()
        val detail = Gson().fromJson(json, ComandaDetailResponse::class.java)
        assertNotNull(detail.taxSnapshot)
        assertEquals(2.8, detail.taxSnapshot!!.rate!!, 0.0001)
        assertEquals(72.50, detail.subtotal!!, 0.0001)
        assertEquals(2.03, detail.taxAmount!!, 0.0001)
        assertEquals(74.53, detail.total, 0.0001)
        assertEquals(24.13, detail.totalPagoBase!!, 0.0001)
        assertEquals(50.40, detail.saldoBase!!, 0.0001)
    }
}
