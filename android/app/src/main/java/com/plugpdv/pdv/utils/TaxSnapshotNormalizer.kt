package com.plugpdv.pdv.utils

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.plugpdv.pdv.models.TaxSnapshotDto

/** Presentation-only normalization; never supplies monetary values. */
object TaxSnapshotNormalizer {
    private val gson = Gson()

    fun first(element: JsonElement?): TaxSnapshotDto? {
        if (element == null || element is JsonNull || element.isJsonNull) return null
        val candidate: JsonObject? = when {
            element.isJsonArray -> element.asJsonArray.firstOrNull { it.isJsonObject }?.asJsonObject
            element.isJsonObject -> element.asJsonObject
            else -> null
        }
        return candidate?.let { runCatching { gson.fromJson(it, TaxSnapshotDto::class.java) }.getOrNull() }
    }
}
