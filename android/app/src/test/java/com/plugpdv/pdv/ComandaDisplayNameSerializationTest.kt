package com.plugpdv.pdv

import com.google.gson.Gson
import com.plugpdv.pdv.models.ComandasListResponse
import org.junit.Assert.assertEquals
import org.junit.Test

class ComandaDisplayNameSerializationTest {
    @Test fun listDisplayNameMapsToNomeCliente() {
        val response = Gson().fromJson(
            """{"total":1,"comandas":[{"id":"standalone-1","mesa_id":null,"status":"EM_CONSUMO","numero":null,"display_name":"Evandro teste 2"}]}""",
            ComandasListResponse::class.java
        )
        assertEquals("Evandro teste 2", response.comandas.single().nomeCliente)
    }

    @Test fun listLegacyNomeClienteRemainsCompatible() {
        val response = Gson().fromJson(
            """{"total":1,"comandas":[{"id":"legacy-1","mesa_id":null,"status":"ABERTA","numero":null,"nome_cliente":"Evandro"}]}""",
            ComandasListResponse::class.java
        )
        assertEquals("Evandro", response.comandas.single().nomeCliente)
    }

    @Test fun listAdditionalAliasesRemainCompatible() {
        listOf("nome", "apelido").forEach { field ->
            val response = Gson().fromJson(
                """{"total":1,"comandas":[{"id":"alias-1","mesa_id":null,"status":"ABERTA","$field":"Evandro"}]}""",
                ComandasListResponse::class.java
            )
            assertEquals("Evandro", response.comandas.single().nomeCliente)
        }
    }
}
