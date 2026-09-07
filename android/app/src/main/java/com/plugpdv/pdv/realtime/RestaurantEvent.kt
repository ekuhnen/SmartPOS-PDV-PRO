package com.plugpdv.pdv.realtime

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** Exact REALTIME_OPS_01B envelope. No business or financial values are decoded. */
data class RestaurantEvent(
    val id: String,
    val serverSeq: Long?,
    val ownerUserId: String,
    val eventType: String,
    val mesaId: String?,
    val comandaId: String?,
    val comandaVersao: Int?,
    val occurredAt: String?
) {
    companion object {
        fun decode(record: JsonObject): RestaurantEvent? {
            fun field(name: String) = record[name] as? JsonPrimitive
            fun text(name: String) = field(name)?.contentOrNull?.takeIf { it.isNotBlank() }
            val type = text("event_type") ?: return null
            when (type) {
                "TABLE_CHANGED", "COMANDA_CHANGED", "COMANDA_ITEMS_CHANGED", "PAYMENT_CHANGED" -> Unit
                else -> return null // Forward-compatible; canonical safety refresh remains active.
            }
            return RestaurantEvent(
                id = text("id") ?: return null,
                serverSeq = field("server_seq")?.longOrNull,
                ownerUserId = text("owner_user_id") ?: return null,
                eventType = type,
                mesaId = text("mesa_id"),
                comandaId = text("comanda_id"),
                comandaVersao = field("comanda_versao")?.intOrNull,
                occurredAt = text("occurred_at")
            )
        }
    }
}
