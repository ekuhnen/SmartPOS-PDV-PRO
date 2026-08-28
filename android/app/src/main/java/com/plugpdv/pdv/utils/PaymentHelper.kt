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

        // 1. A moeda da transação selecionada DEVE ter exatamente o valor cotado via protocolo minor units
        val transFormatted = MoneyDecimal.toProtocolAmount(transactionAmount, transactionCurrency)
        json.put(transactionCurrency.uppercase(Locale.ROOT), transFormatted)

        // 2. Moeda base da comanda / venda direta via protocolo minor units
        val baseFormatted = MoneyDecimal.toProtocolAmount(baseAmount, baseCurrency)
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
                    val formatted = MoneyDecimal.toProtocolAmount(converted, code)
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
