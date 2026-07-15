package com.plugpdv.pdv.utils

import com.plugpdv.pdv.models.ExchangeResponse
import java.text.NumberFormat
import java.util.*

class CurrencyManager private constructor() {
    private var rates: ExchangeResponse? = null
    var selectedCurrency: String = "BRL"
        set(value) {
            field = value
        }

    fun setRates(rates: ExchangeResponse?) {
        this.rates = rates
        if (rates != null && rates.moeda_principal != null) {
            // Se não houver seleção prévia, usa a principal
            if (selectedCurrency == "BRL" && rates.moeda_principal != "BRL") {
                selectedCurrency = rates.moeda_principal
            }
        }
    }

    fun getBaseCurrency(): String {
        return rates?.moeda_principal ?: "BRL"
    }

    fun getAvailableCurrencies(): List<ExchangeResponse.CurrencyRate> {
        return rates?.moedas ?: emptyList()
    }

    fun convert(valueInBrl: Double): Double {
        val currentRates = rates?.moedas ?: return valueInBrl
        
        for (rate in currentRates) {
            if (rate.codigo.equals(selectedCurrency, ignoreCase = true)) {
                return valueInBrl * rate.taxa
            }
        }
        return valueInBrl
    }

    fun convertToBrl(valueInSelectedCurrency: Double): Double {
        val currentRates = rates?.moedas ?: return valueInSelectedCurrency
        
        for (rate in currentRates) {
            if (rate.codigo.equals(selectedCurrency, ignoreCase = true)) {
                if (rate.taxa == 0.0) return valueInSelectedCurrency
                return valueInSelectedCurrency / rate.taxa
            }
        }
        return valueInSelectedCurrency
    }

    fun format(valueInBrl: Double): String {
        return formatExplicit(convert(valueInBrl), selectedCurrency)
    }

    fun formatExplicit(value: Double, currencyCode: String): String {
        return try {
            val locale = getLocaleForCurrency(currencyCode)
            val format = NumberFormat.getCurrencyInstance(locale)
            format.currency = Currency.getInstance(currencyCode)
            
            // Especial handling for PYG (Gs.) and ARS ($) - no decimals and round up
            if (currencyCode.equals("PYG", ignoreCase = true) || currencyCode.equals("ARS", ignoreCase = true)) {
                format.maximumFractionDigits = 0
                format.minimumFractionDigits = 0
                val ceiledValue = Math.ceil(value)
                return format.format(ceiledValue)
            }
            
            format.format(value)
        } catch (e: Exception) {
            "$currencyCode ${String.format("%.2f", value)}"
        }
    }

    private fun getLocaleForCurrency(currencyCode: String): Locale {
        return when (currencyCode.uppercase()) {
            "USD" -> Locale.US
            "BRL" -> Locale("pt", "BR")
            "PYG" -> Locale("es", "PY")
            "EUR" -> Locale.GERMANY
            "ARS" -> Locale("es", "AR")
            else -> Locale.getDefault()
        }
    }

    companion object {
        @Volatile
        private var instance: CurrencyManager? = null

        @JvmStatic
        fun getInstance(): CurrencyManager {
            return instance ?: synchronized(this) {
                instance ?: CurrencyManager().also { instance = it }
            }
        }
    }
}
