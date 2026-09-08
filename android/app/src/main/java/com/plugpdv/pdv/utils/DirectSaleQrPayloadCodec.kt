package com.plugpdv.pdv.utils

import android.util.Base64
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.StringReader
import java.io.StringWriter
import java.math.BigDecimal
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/** Unit price is a decimal in [currency], never a localized price or an FX input. */
data class DirectSaleQrPayload(
    val saleId: String,
    val productId: String,
    val productName: String,
    val quantity: Int,
    val unitPrice: BigDecimal,
    val currency: String,
    val issuedAt: String,
    val copy: Int,
    val operatorName: String? = null,
    val v: Int = 1
)

/** PDV1 is encoding, not encryption or proof of payment. See DIRECT-SALE-QR-CONTRACT.md. */
object DirectSaleQrPayloadCodec {
    const val PREFIX = "PDV1:"
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING

    fun encode(payload: DirectSaleQrPayload): String {
        validate(payload)
        val out = StringWriter()
        JsonWriter(out).use { w ->
            w.beginObject()
            w.name("v").value(payload.v)
            w.name("sale_id").value(payload.saleId)
            w.name("product_id").value(payload.productId)
            w.name("product_name").value(payload.productName)
            w.name("quantity").value(payload.quantity)
            w.name("unit_price").jsonValue(payload.unitPrice.toPlainString())
            w.name("currency").value(payload.currency)
            w.name("issued_at").value(payload.issuedAt)
            w.name("copy").value(payload.copy)
            payload.operatorName?.let { w.name("operator_name").value(it) }
            w.endObject()
        }
        return PREFIX + Base64.encodeToString(out.toString().toByteArray(Charsets.UTF_8), FLAGS)
    }

    /** Rejects legacy, malformed input and unsupported versions; never guesses fields. */
    fun decode(rawQr: String): DirectSaleQrPayload {
        try {
            require(rawQr.startsWith(PREFIX)) { "Unsupported QR prefix" }
            val encoded = rawQr.removePrefix(PREFIX)
            require(encoded.matches(Regex("[A-Za-z0-9_-]+"))) { "Invalid Base64url" }
            val bytes = Base64.decode(encoded, FLAGS)
            require(Base64.encodeToString(bytes, FLAGS) == encoded) { "Noncanonical Base64url" }
            val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            val fields = mutableMapOf<String, String>()
            val strings = setOf("sale_id", "product_id", "product_name", "currency", "issued_at", "operator_name")
            val numbers = setOf("v", "quantity", "unit_price", "copy")
            JsonReader(StringReader(json)).use { r ->
                r.isLenient = false
                r.beginObject()
                val seen = mutableSetOf<String>()
                while (r.hasNext()) {
                    val name = r.nextName()
                    require(seen.add(name)) { "Duplicate field" }
                    when (name) {
                        in strings -> { require(r.peek() == JsonToken.STRING); fields[name] = r.nextString() }
                        in numbers -> { require(r.peek() == JsonToken.NUMBER); fields[name] = r.nextString() }
                        else -> r.skipValue() // Additive metadata is safe to ignore.
                    }
                }
                r.endObject()
                require(r.peek() == JsonToken.END_DOCUMENT)
            }
            fun required(name: String) = requireNotNull(fields[name]) { "Missing $name" }
            return DirectSaleQrPayload(
                saleId = required("sale_id"), productId = required("product_id"),
                productName = required("product_name"), quantity = required("quantity").toInt(),
                unitPrice = required("unit_price").toBigDecimal(), currency = required("currency"),
                issuedAt = required("issued_at"), copy = required("copy").toInt(),
                operatorName = fields["operator_name"], v = required("v").toInt()
            ).also(::validate)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid PDV1 payload", e)
        }
    }

    private fun validate(p: DirectSaleQrPayload) {
        require(p.v == 1) { "Unsupported version" }
        require(p.saleId.isNotBlank() && p.productId.isNotBlank() && p.productName.isNotBlank())
        require(p.quantity > 0 && p.copy > 0 && p.unitPrice.signum() >= 0)
        require(p.currency.matches(Regex("[A-Z]{3}")))
        require(p.issuedAt.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")))
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }.parse(p.issuedAt)
    }
}
