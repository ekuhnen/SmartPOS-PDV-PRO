package com.plugpdv.pdv.utils

import com.plugpdv.pdv.database.TaxEntity
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Locale

object PaymentHelper {

    /**
     * Geração determinística de amountsJson baseada EXCLUSIVAMENTE em BigDecimals
     * e no snapshot congelado de taxas de câmbio (sem segunda consulta ao CurrencyManager,
     * sem Double, sem Math.ceil e sem hardcode de moedas).
     */
    fun generateAmountsJsonExact(
        baseAmount: BigDecimal,
        baseCurrency: String,
        transactionCurrency: String,
        transactionAmount: BigDecimal,
        snapshot: Map<String, String>?,
        activeTaxes: List<TaxEntity> = emptyList()
    ): String {
        val json = JSONObject()

        // 1. A moeda da transação selecionada DEVE ter exatamente o valor cotado
        val transDecimals = MoneyDecimal.getDisplayDecimals(transactionCurrency)
        val transRounded = MoneyDecimal.roundToCurrency(transactionAmount, transactionCurrency)
        val transFormatted = if (transDecimals == 0) {
            transRounded.toBigInteger().toString()
        } else {
            transRounded.setScale(transDecimals, RoundingMode.HALF_UP).toPlainString()
        }
        json.put(transactionCurrency.uppercase(Locale.ROOT), transFormatted)

        // 2. Moeda base da comanda / venda direta
        val baseDecimals = MoneyDecimal.getDisplayDecimals(baseCurrency)
        val baseRounded = MoneyDecimal.roundToCurrency(baseAmount, baseCurrency)
        val baseFormatted = if (baseDecimals == 0) {
            baseRounded.toBigInteger().toString()
        } else {
            baseRounded.setScale(baseDecimals, RoundingMode.HALF_UP).toPlainString()
        }
        json.put(baseCurrency.uppercase(Locale.ROOT), baseFormatted)

        // 3. Demais moedas do snapshot congelado (sem nova consulta ao CurrencyManager)
        if (snapshot != null) {
            for ((currencyCode, rateStr) in snapshot) {
                val code = currencyCode.uppercase(Locale.ROOT)
                if (code == transactionCurrency.uppercase(Locale.ROOT) || code == baseCurrency.uppercase(Locale.ROOT)) {
                    continue
                }
                val rate = runCatching { BigDecimal(rateStr) }.getOrNull()
                if (rate != null && rate > BigDecimal.ZERO) {
                    val converted = baseAmount.multiply(rate)
                    val decimals = MoneyDecimal.getDisplayDecimals(code)
                    val rounded = MoneyDecimal.roundToCurrency(converted, code)
                    val formatted = if (decimals == 0) {
                        rounded.toBigInteger().toString()
                    } else {
                        rounded.setScale(decimals, RoundingMode.HALF_UP).toPlainString()
                    }
                    json.put(code, formatted)
                }
            }
        }

        return json.toString()
    }

    @Deprecated("Substituído por generateAmountsJsonExact para eliminar ponto flutuante e chamadas redundantes de FX")
    fun generateAmountsJson(
        inputAmountBrl: Double,
        selectedCurrency: String,
        activeTaxes: List<TaxEntity>,
        currencyManager: CurrencyManager
    ): String {
        return generateAmountsJsonExact(
            baseAmount = BigDecimal.valueOf(inputAmountBrl),
            baseCurrency = "BRL",
            transactionCurrency = selectedCurrency,
            transactionAmount = BigDecimal.valueOf(inputAmountBrl),
            snapshot = null,
            activeTaxes = activeTaxes
        )
    }
}
