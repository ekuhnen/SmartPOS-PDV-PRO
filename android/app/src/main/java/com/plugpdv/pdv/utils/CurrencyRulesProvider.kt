package com.plugpdv.pdv.utils

import com.plugpdv.pdv.models.CurrencyCapability
import com.plugpdv.pdv.models.ExchangeResponse
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.pow
import kotlin.math.roundToLong

interface CurrencyRulesProvider {
    fun getBaseCurrency(): String
    fun getAvailableCurrencies(): List<ExchangeResponse.CurrencyRate>
    fun getRateForCurrency(code: String): Double
    fun getDecimalPlaces(currencyCode: String): Int
    fun getMinorUnitDigits(currencyCode: String): Int
    fun getDisplayDecimals(currencyCode: String): Int
    fun isZeroDecimal(currencyCode: String): Boolean
    fun setCapabilities(capabilities: Map<String, CurrencyCapability>)
    fun getCapability(currencyCode: String): CurrencyCapability
    fun formatExplicit(amount: Double, currencyCode: String): String
    fun formatMinorUnits(amountMinorUnits: Long, currencyCode: String): String
    fun formatKeypadInput(rawDigits: String, currencyCode: String): String
}

@Singleton
class DefaultCurrencyRulesProvider @Inject constructor() : CurrencyRulesProvider {

    private val capabilitiesMap = java.util.concurrent.ConcurrentHashMap<String, CurrencyCapability>()

    init {
        // Regras default padronizadas enquanto capabilities não forem carregadas da API
        capabilitiesMap["BRL"] = CurrencyCapability(
            currencyCode = "BRL",
            symbol = "R$",
            symbolPosition = "PREFIX",
            thousandsSeparator = ".",
            decimalSeparator = ",",
            displayDecimals = 2,
            minorUnitDigits = 2
        )
        capabilitiesMap["USD"] = CurrencyCapability(
            currencyCode = "USD",
            symbol = "$",
            symbolPosition = "PREFIX",
            thousandsSeparator = ",",
            decimalSeparator = ".",
            displayDecimals = 2,
            minorUnitDigits = 2
        )
        capabilitiesMap["PYG"] = CurrencyCapability(
            currencyCode = "PYG",
            symbol = "Gs.",
            symbolPosition = "PREFIX",
            thousandsSeparator = ".",
            decimalSeparator = ",",
            displayDecimals = 0,
            minorUnitDigits = 0
        )
        capabilitiesMap["ARS"] = CurrencyCapability(
            currencyCode = "ARS",
            symbol = "$",
            symbolPosition = "PREFIX",
            thousandsSeparator = ".",
            decimalSeparator = ",",
            displayDecimals = 0,
            minorUnitDigits = 2
        )
        capabilitiesMap["EUR"] = CurrencyCapability(
            currencyCode = "EUR",
            symbol = "€",
            symbolPosition = "SUFFIX",
            thousandsSeparator = ".",
            decimalSeparator = ",",
            displayDecimals = 2,
            minorUnitDigits = 2
        )
    }

    override fun getBaseCurrency(): String {
        return CurrencyManager.getInstance().getBaseCurrency()
    }

    override fun getAvailableCurrencies(): List<ExchangeResponse.CurrencyRate> {
        return CurrencyManager.getInstance().getAvailableCurrencies()
    }

    override fun getRateForCurrency(code: String): Double {
        return CurrencyManager.getInstance().getRateForCurrency(code)
    }

    override fun getDecimalPlaces(currencyCode: String): Int {
        return getMinorUnitDigits(currencyCode)
    }

    override fun getMinorUnitDigits(currencyCode: String): Int {
        return getCapability(currencyCode).minorUnitDigits
    }

    override fun getDisplayDecimals(currencyCode: String): Int {
        return getCapability(currencyCode).displayDecimals
    }

    override fun isZeroDecimal(currencyCode: String): Boolean {
        return getDisplayDecimals(currencyCode) == 0
    }

    override fun setCapabilities(capabilities: Map<String, CurrencyCapability>) {
        capabilities.forEach { (code, cap) ->
            capabilitiesMap[code.uppercase()] = cap
        }
    }

    override fun getCapability(currencyCode: String): CurrencyCapability {
        val codeUpper = currencyCode.uppercase()
        return capabilitiesMap[codeUpper] ?: CurrencyCapability(
            currencyCode = codeUpper,
            symbol = codeUpper,
            symbolPosition = "PREFIX",
            thousandsSeparator = ".",
            decimalSeparator = ",",
            displayDecimals = if (codeUpper == "PYG" || codeUpper == "ARS") 0 else 2,
            minorUnitDigits = if (codeUpper == "PYG" || codeUpper == "CLP") 0 else 2
        )
    }

    override fun formatExplicit(amount: Double, currencyCode: String): String {
        val cap = getCapability(currencyCode)
        val factor = 10.0.pow(cap.displayDecimals)
        val minorUnits = (amount * factor).roundToLong()
        return formatMinorUnits(minorUnits, currencyCode)
    }

    override fun formatMinorUnits(amountMinorUnits: Long, currencyCode: String): String {
        val cap = getCapability(currencyCode)
        val isNegative = amountMinorUnits < 0
        val absAmount = Math.abs(amountMinorUnits)

        val formattedNumber = if (cap.displayDecimals > 0) {
            val divisor = 10.0.pow(cap.displayDecimals).toLong()
            val integerPart = absAmount / divisor
            val decimalPart = (absAmount % divisor).toString().padStart(cap.displayDecimals, '0')
            val integerFormatted = formatIntegerWithSeparator(integerPart, cap.thousandsSeparator)
            "$integerFormatted${cap.decimalSeparator}$decimalPart"
        } else {
            formatIntegerWithSeparator(absAmount, cap.thousandsSeparator)
        }

        val sign = if (isNegative) "-" else ""
        val withSymbol = if (cap.symbolPosition.equals("SUFFIX", ignoreCase = true)) {
            "$sign$formattedNumber ${cap.symbol}"
        } else {
            "$sign${cap.symbol} $formattedNumber"
        }

        return withSymbol
    }

    override fun formatKeypadInput(rawDigits: String, currencyCode: String): String {
        val cleanDigits = rawDigits.filter { it.isDigit() }
        if (cleanDigits.isEmpty()) {
            return formatMinorUnits(0L, currencyCode)
        }
        val minorUnits = cleanDigits.toLongOrNull() ?: 0L
        return formatMinorUnits(minorUnits, currencyCode)
    }

    private fun formatIntegerWithSeparator(number: Long, separator: String): String {
        val str = number.toString()
        val builder = StringBuilder()
        var count = 0
        for (i in str.length - 1 downTo 0) {
            builder.append(str[i])
            count++
            if (count % 3 == 0 && i != 0) {
                builder.append(separator)
            }
        }
        return builder.reverse().toString()
    }
}
