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

    @Volatile
    private var rulesProvider: CurrencyRulesProvider? = null

    fun setRulesProvider(provider: CurrencyRulesProvider) {
        rulesProvider = provider
    }

    private val ISO_ZERO_DECIMALS = setOf(
        "BIF", "CLP", "DJF", "GNF", "ISK", "JPY", "KMF", "KRW",
        "PYG", "RWF", "UGX", "UYI", "VND", "VUV", "XAF", "XOF", "XPF"
    )

    private val ISO_THREE_DECIMALS = setOf(
        "BHD", "IQD", "JOD", "KWD", "LYD", "OMR", "TND"
    )

    private val ISO_TWO_DECIMALS = setOf(
        "BRL", "USD", "EUR", "ARS", "GBP", "MXN", "COP", "PEN",
        "UYU", "BOB", "CAD", "AUD", "CHF", "CNY"
    )

    fun of(value: Long): BigDecimal = BigDecimal.valueOf(value)
    fun of(value: String): BigDecimal = BigDecimal(value)
    fun of(value: Double): BigDecimal = BigDecimal.valueOf(value)

    fun getDecimals(currencyCode: String): Int {
        val upper = currencyCode.uppercase()
        // 1. Dynamic capability from rules provider takes precedence
        rulesProvider?.let { provider ->
            return provider.getMinorUnitDigits(upper)
        }
        // 2. Fallback to audited ISO 4217 table
        return when {
            ISO_ZERO_DECIMALS.contains(upper) -> 0
            ISO_THREE_DECIMALS.contains(upper) -> 3
            ISO_TWO_DECIMALS.contains(upper) -> 2
            else -> 2 // standard ISO fallback
        }
    }

    fun getDisplayDecimals(currencyCode: String): Int {
        val upper = currencyCode.uppercase()
        rulesProvider?.let { provider ->
            return provider.getDisplayDecimals(upper)
        }
        return if (upper == "PYG" || upper == "ARS" || ISO_ZERO_DECIMALS.contains(upper)) 0 else 2
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
        val decimals = if (currencyCode != null) getDecimals(currencyCode) else 8
        return a.divide(b, decimals, RoundingMode.HALF_UP)
    }
}
