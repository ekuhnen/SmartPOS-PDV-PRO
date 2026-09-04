package com.plugpdv.pdv

import com.google.gson.Gson
import com.plugpdv.pdv.models.ComandaDetailResponse
import com.plugpdv.pdv.utils.TaxSnapshotNormalizer
import org.junit.Assert.assertEquals
import org.junit.Test

class ComandaTaxSnapshotContractTest {
    @Test
    fun arrayObjectNullAndMissingSnapshotsPreserveAuthoritativeMoney() {
        val fields = listOf(
            "\"tax_snapshot\":[{\"tax_id\":\"t1\",\"name\":\"ICMS\",\"rate\":2.8,\"currency\":\"BRL\",\"amount\":2.03}]",
            "\"tax_snapshot\":{\"tax_id\":\"t1\",\"name\":\"ICMS\",\"rate\":2.8,\"currency\":\"BRL\",\"amount\":2.03}",
            "\"tax_snapshot\":null",
            null
        )
        fields.forEach { field ->
            val optional = field?.let { "$it,\n" }.orEmpty()
            val json = """
                {"id":"c1","status":"ABERTA","total":74.53,"subtotal":72.50,
                 "tax_amount":2.03,$optional"total_pago_base":24.13,"saldo_base":50.40,"itens":[]}
            """.trimIndent()
            val detail = Gson().fromJson(json, ComandaDetailResponse::class.java)
            val normalized = TaxSnapshotNormalizer.first(detail.taxSnapshot)
            if (field == null || field.contains(":null")) assertEquals(null, normalized)
            else assertEquals(2.8, normalized!!.rate!!, 0.0001)
            assertEquals(72.50, detail.subtotal!!, 0.0001)
            assertEquals(2.03, detail.taxAmount!!, 0.0001)
            assertEquals(74.53, detail.total, 0.0001)
            assertEquals(24.13, detail.totalPagoBase!!, 0.0001)
            assertEquals(50.40, detail.saldoBase!!, 0.0001)
        }
    }
}
