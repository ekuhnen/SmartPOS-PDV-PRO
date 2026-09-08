package com.plugpdv.pdv.utils

import java.security.MessageDigest

/** Informational manual reference for one redeemable direct-sale unit. */
object DirectSalePickupCode {
    fun forTicket(saleId: String, productId: String, copy: Int): String {
        require(copy > 0)
        val input = "PDV1|$saleId|$productId|$copy"
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        val hex = digest.take(4).joinToString("") { "%02X".format(it) }
        return "${hex.substring(0, 4)}-${hex.substring(4, 8)}"
    }
}
