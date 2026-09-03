package com.plugpdv.pdv.utils

/** Server-provided comanda money projection. No tax, fee, or FX is derived locally. */
data class ComandaMoneyAuthority(
    val subtotal: Double?,
    val taxAmount: Double?,
    val serviceFee: Double?,
    val total: Double?,
    val paid: Double?,
    val saldo: Double?
) {
    fun payableAmount(): Double = saldo?.coerceAtLeast(0.0) ?: 0.0
}
