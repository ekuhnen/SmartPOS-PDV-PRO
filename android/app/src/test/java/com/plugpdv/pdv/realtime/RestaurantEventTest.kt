package com.plugpdv.pdv.realtime

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class RestaurantEventTest {
    private fun decode(text: String) = RestaurantEvent.decode(Json.parseToJsonElement(text).jsonObject)

    @Test fun exactContractFieldsAndNullIdentifiers() {
        val event = decode("""{"id":"event","server_seq":105,"owner_user_id":"owner",
            "event_type":"TABLE_CHANGED","mesa_id":"mesa","comanda_id":null,
            "comanda_versao":null,"occurred_at":"2026-09-07T12:00:00Z","tx_id":12}""")!!
        assertEquals(105L, event.serverSeq)
        assertEquals("mesa", event.mesaId)
        assertNull(event.comandaId)
        assertNull(event.comandaVersao)
    }

    @Test fun unknownAndMalformedEventsDegradeGracefully() {
        assertNull(decode("""{"id":"e","owner_user_id":"o","event_type":"NEW_TYPE"}"""))
        assertNull(decode("""{"id":"e","event_type":"TABLE_CHANGED"}"""))
        assertNull(decode("""{"event_type":{},"id":"e","owner_user_id":"o"}"""))
    }

    @Test fun paymentEnvelopeCannotCarryBusinessAuthority() {
        val event = decode("""{"id":"e","owner_user_id":"o","event_type":"PAYMENT_CHANGED",
            "comanda_id":"c","comanda_versao":4,"total":999,"payment_result":"approved"}""")!!
        assertEquals("c", event.comandaId)
        assertEquals(4, event.comandaVersao)
        assertFalse(event.toString().contains("approved"))
        assertFalse(event.toString().contains("999"))
    }
}
