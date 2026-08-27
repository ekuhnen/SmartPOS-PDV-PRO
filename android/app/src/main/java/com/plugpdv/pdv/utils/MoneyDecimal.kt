package com.plugpdv.pdv.utils

import java.math.BigDecimal
import java.math.RoundingMode

data class MoneyQuote(
    val transactionAmount: BigDecimal,
    val transactionCurrency: String,
    val baseAmount: BigDecimal,
    val baseCurrency: String,
    val fxRate: BigDecimal,
    val snapshot: Map<String, String>? = null
)

object MoneyDecimal {

    fun of(value: Long): BigDecimal = BigDecimal.valueOf(value)
    fun of(value: String): BigDecimal = BigDecimal(value)
    fun of(value: Double): BigDecimal = BigDecimal.valueOf(value)

    fun getDecimals(currencyCode: String): Int {
        val code = currencyCode.uppercase()
        return when (code) {
            "PYG", "CLP" -> 0
            else -> 2 // BRL, USD, EUR, ARS minor units
        }
    }

    fun roundToCurrency(
        amount: BigDecimal,
        currencyCode: String,
        mode: RoundingMode = RoundingMode.HALF_UP
    ): BigDecimal {
        val decimals = getDecimals(currencyCode)
        return amount.setScale(decimals, mode)
    }

    fun toMinorUnits(amount: BigDecimal, currencyCode: String): Long {
        val decimals = getDecimals(currencyCode)
        val scaled = amount.setScale(decimals, RoundingMode.HALF_UP)
        val factor = BigDecimal.TEN.pow(decimals)
        return scaled.multiply(factor).toLong()
    }

    fun fromMinorUnits(minorUnits: Long, currencyCode: String): BigDecimal {
        val decimals = getDecimals(currencyCode)
        val factor = BigDecimal.TEN.pow(decimals)
        return BigDecimal.valueOf(minorUnits).divide(factor, decimals, RoundingMode.HALF_UP)
    }

    fun isEqual(a: BigDecimal, b: BigDecimal): Boolean {
        return a.compareTo(b) == 0
    }

    fun add(a: BigDecimal, b: BigDecimal, currencyCode: String? = null): BigDecimal {
        val res = a.add(b)
        return if (currencyCode != null) roundToCurrency(res, currencyCode) else res
    }

    fun subtract(a: BigDecimal, b: BigDecimal, currencyCode: String? = null): BigDecimal {
        val res = a.subtract(b)
        return if (currencyCode != null) roundToCurrency(res, currencyCode) else res
    }

    fun multiply(a: BigDecimal, b: BigDecimal, currencyCode: String? = null): BigDecimal {
        val res = a.multiply(b)
        return if (currencyCode != null) roundToCurrency(res, currencyCode) else res
    }

    fun divide(a: BigDecimal, b: BigDecimal, currencyCode: String? = null): BigDecimal {
        val decimals = if (currencyCode != null) getDecimals(currencyCode) else 6
        return a.divide(b, decimals, RoundingMode.HALF_UP)
    }
}
