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
     * Retorna a taxa relativa ao BRL para uma moeda como BigDecimal exato.
     * Retorna null se não houver taxa registrada (fail-closed).
     */
    fun getRateForCurrencyExact(code: String): java.math.BigDecimal? {
        val upper = code.uppercase()
        if (upper == "BRL") return java.math.BigDecimal.ONE
        val currentRates = rates?.moedas ?: return null
        for (rate in currentRates) {
            if (rate.codigo.equals(upper, ignoreCase = true)) {
                return if (rate.taxa != 0.0) java.math.BigDecimal.valueOf(rate.taxa) else null
            }
        }
        return null
    }

    /**
     * Normaliza todas as cotações para a moeda-base especificada (Option A).
     * Retorna mapa determinístico de strings decimais canônicas.
     */
    fun getNormalizedSnapshotForBase(baseCurrency: String): Map<String, String> {
        val upperBase = baseCurrency.uppercase()
        val result = mutableMapOf<String, String>()
        val baseRateToBrl = getRateForCurrencyExact(upperBase) ?: java.math.BigDecimal.ONE

        result[upperBase] = "1"
        if (upperBase != "BRL") {
            val brlPerBase = java.math.BigDecimal.ONE.divide(baseRateToBrl, 6, java.math.RoundingMode.HALF_UP)
            result["BRL"] = brlPerBase.stripTrailingZeros().toPlainString()
        }

        rates?.moedas?.forEach { r ->
            val code = r.codigo.uppercase()
            if (code != upperBase) {
                val rRateToBrl = java.math.BigDecimal.valueOf(r.taxa)
                val ratePerBase = rRateToBrl.divide(baseRateToBrl, 6, java.math.RoundingMode.HALF_UP)
                result[code] = ratePerBase.stripTrailingZeros().toPlainString()
            }
        }
        return result
    }

    /**
     * Converte valor financeiro com precisão estrita BigDecimal, fail-closed e normalização.
     */
    fun convertMoneyExact(
        amount: java.math.BigDecimal,
        fromCurrency: String,
        toCurrency: String,
        baseCurrency: String? = null
    ): Result<MoneyQuote> {
        val fromUpper = fromCurrency.uppercase()
        val toUpper = toCurrency.uppercase()
        val targetBase = (baseCurrency ?: getBaseCurrency()).uppercase()

        if (fromUpper == toUpper) {
            val quote = MoneyQuote(
                transactionAmount = MoneyDecimal.roundToCurrency(amount, toUpper),
                transactionCurrency = toUpper,
                baseAmount = MoneyDecimal.roundToCurrency(amount, targetBase),
                baseCurrency = targetBase,
                fxRate = java.math.BigDecimal.ONE,
                snapshot = getNormalizedSnapshotForBase(targetBase)
            )
            return Result.success(quote)
        }

        val rateFromBrl = getRateForCurrencyExact(fromUpper)
            ?: return Result.failure(IllegalStateException("FX_RATE_MISSING: No rate for $fromUpper"))
        val rateToBrl = getRateForCurrencyExact(toUpper)
            ?: return Result.failure(IllegalStateException("FX_RATE_MISSING: No rate for $toUpper"))

        // Cross-rate: unidades de toCurrency por 1 unidade de fromCurrency
        val crossRate = rateToBrl.divide(rateFromBrl, 8, java.math.RoundingMode.HALF_UP)
        val convertedAmount = amount.multiply(crossRate)

        // Se a moeda de destino for a moeda da transação e a origem a base:
        val fxRateForBase = if (fromUpper == targetBase) crossRate else {
            val rateTargetBaseBrl = getRateForCurrencyExact(targetBase) ?: java.math.BigDecimal.ONE
            rateToBrl.divide(rateTargetBaseBrl, 8, java.math.RoundingMode.HALF_UP)
        }

        val baseAmount = if (fromUpper == targetBase) {
            MoneyDecimal.roundToCurrency(amount, targetBase)
        } else {
            val amountInBrl = amount.divide(rateFromBrl, 8, java.math.RoundingMode.HALF_UP)
            val rateTargetBaseBrl = getRateForCurrencyExact(targetBase) ?: java.math.BigDecimal.ONE
            MoneyDecimal.roundToCurrency(amountInBrl.multiply(rateTargetBaseBrl), targetBase)
        }

        val quote = MoneyQuote(
            transactionAmount = MoneyDecimal.roundToCurrency(convertedAmount, toUpper),
            transactionCurrency = toUpper,
            baseAmount = baseAmount,
            baseCurrency = targetBase,
            fxRate = fxRateForBase.stripTrailingZeros(),
            snapshot = getNormalizedSnapshotForBase(targetBase)
        )
        return Result.success(quote)
    }

    /**
     * Retorna a taxa relativa ao BRL para uma moeda.
     * Na API de câmbio, a base de todas as cotações é "BRL".
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
     */
    fun toBrl(amount: Double, fromCurrency: String): Double {
        if (fromCurrency.equals("BRL", ignoreCase = true)) return amount
        val rate = getRateForCurrency(fromCurrency)
        return amount / rate
    }

    /**
     * Converte QUALQUER valor em BRL para a moeda 'toCurrency'.
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
