package com.plugpdv.pdv.utils

import android.content.Context
import com.google.gson.Gson
import com.plugpdv.pdv.models.ExchangeResponse
import java.text.NumberFormat
import java.util.*

class CurrencyManager private constructor() {
    private var rates: ExchangeResponse? = null
    var selectedCurrency: String = "BRL"

    fun init(context: Context) {
        if (rates == null) {
            val prefs = context.getSharedPreferences("currency_prefs", Context.MODE_PRIVATE)
            val json = prefs.getString("exchange_rates", null)
            if (!json.isNullOrEmpty()) {
                try {
                    val loaded = Gson().fromJson(json, ExchangeResponse::class.java)
                    setRatesInMemory(loaded, context, save = false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    fun setRates(context: Context, rates: ExchangeResponse?) {
        setRatesInMemory(rates, context, save = true)
    }

    fun setRates(rates: ExchangeResponse?) {
        setRatesInMemory(rates, null, save = false)
    }

    private fun setRatesInMemory(rates: ExchangeResponse?, context: Context?, save: Boolean) {
        this.rates = rates
        if (rates != null && !rates.moeda_principal.isNullOrEmpty()) {
            if (selectedCurrency == "BRL" && rates.moeda_principal != "BRL") {
                selectedCurrency = rates.moeda_principal
            }
        }
        if (save && context != null && rates != null) {
            try {
                val json = Gson().toJson(rates)
                context.getSharedPreferences("currency_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("exchange_rates", json)
                    .apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getBaseCurrency(): String {
        return rates?.moeda_principal ?: "BRL"
    }

    fun getAvailableCurrencies(): List<ExchangeResponse.CurrencyRate> {
        return rates?.moedas ?: emptyList()
    }

    /**
     * Retorna a taxa relativa ao BRL para uma moeda.
     * Na API de câmbio, a base de todas as cotações é "BRL".
     * Exemplo: BRL=1.0, PYG=1189.82, USD=0.20
     */
    fun getRateForCurrency(code: String): Double {
        if (code.equals("BRL", ignoreCase = true)) return 1.0
        val currentRates = rates?.moedas ?: return 1.0
        for (rate in currentRates) {
            if (rate.codigo.equals(code, ignoreCase = true)) {
                return if (rate.taxa != 0.0) rate.taxa else 1.0
            }
        }
        return 1.0
    }

    /**
     * Converte QUALQUER valor da moeda 'fromCurrency' para BRL.
     * Exemplo: 15.000 PYG / 1189.82 = 12.6069 BRL
     */
    fun toBrl(amount: Double, fromCurrency: String): Double {
        if (fromCurrency.equals("BRL", ignoreCase = true)) return amount
        val rate = getRateForCurrency(fromCurrency)
        return amount / rate
    }

    /**
     * Converte QUALQUER valor em BRL para a moeda 'toCurrency'.
     * Exemplo: 12.6069 BRL * 1189.82 = 15.000 PYG
     */
    fun fromBrl(amountInBrl: Double, toCurrency: String): Double {
        if (toCurrency.equals("BRL", ignoreCase = true)) return amountInBrl
        val rate = getRateForCurrency(toCurrency)
        return amountInBrl * rate
    }

    /**
     * Converte um valor em BRL para a moeda atualmente selecionada no app.
     */
    fun convert(valueInBrl: Double): Double {
        return fromBrl(valueInBrl, selectedCurrency)
    }

    fun convertToBrl(valueInSelectedCurrency: Double): Double {
        return toBrl(valueInSelectedCurrency, selectedCurrency)
    }

    fun convertCurrencyToBase(value: Double, fromCurrency: String): Double {
        return toBrl(value, fromCurrency)
    }

    fun format(valueInBrl: Double): String {
        return formatExplicit(convert(valueInBrl), selectedCurrency)
    }

    private val defaultRulesProvider = DefaultCurrencyRulesProvider()

    fun formatExplicit(value: Double, currencyCode: String): String {
        return defaultRulesProvider.formatExplicit(value, currencyCode)
    }

    fun formatMinorUnits(amountMinorUnits: Long, currencyCode: String): String {
        return defaultRulesProvider.formatMinorUnits(amountMinorUnits, currencyCode)
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
