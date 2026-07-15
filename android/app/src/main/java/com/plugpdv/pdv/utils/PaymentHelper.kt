package com.plugpdv.pdv.utils

import com.plugpdv.pdv.database.TaxEntity
import org.json.JSONObject
import java.util.Locale

object PaymentHelper {

    /**
     * Calcula o valor final exato para TODAS as moedas, aplicando o imposto correto
     * de forma independente para cada moeda.
     *
     * @param inputAmountBrl O valor informado no bottomsheet (em BRL, que inclui o imposto da moeda atual)
     * @param selectedCurrency A moeda que estava selecionada quando o valor foi informado
     * @param activeTaxes A lista de impostos ativos no sistema
     * @param currencyManager O gerenciador de moedas para as taxas de câmbio
     * @return String JSON com os valores finais já calculados para cada moeda
     */
    fun generateAmountsJson(
        inputAmountBrl: Double,
        selectedCurrency: String,
        activeTaxes: List<TaxEntity>,
        currencyManager: CurrencyManager
    ): String {
        // 1. Descobrir qual era o imposto aplicado no valor de entrada
        val selectedTaxPercent = activeTaxes
            .filter { it.currency.equals(selectedCurrency, ignoreCase = true) }
            .sumOf { it.percentage }

        // 2. Extrair a "Base Limpa" em BRL (valor sem nenhum imposto)
        val baseBrl = inputAmountBrl / (1.0 + (selectedTaxPercent / 100.0))

        val json = JSONObject()

        // 3. Recalcular para BRL puro
        val brlTaxPercent = activeTaxes
            .filter { it.currency.equals("BRL", ignoreCase = true) }
            .sumOf { it.percentage }
        val finalBrl = baseBrl * (1.0 + (brlTaxPercent / 100.0))
        json.put("BRL", String.format(Locale.US, "%.2f", finalBrl))

        // 4. Recalcular para as outras moedas disponíveis
        currencyManager.getAvailableCurrencies().forEach { rate ->
            val code = rate.codigo.uppercase()
            
            // Imposto específico da moeda alvo
            val taxPercent = activeTaxes
                .filter { it.currency.equals(code, ignoreCase = true) }
                .sumOf { it.percentage }

            // Aplica o imposto dessa moeda em cima da base BRL
            val finalBrlForCurrency = baseBrl * (1.0 + (taxPercent / 100.0))
            
            // Converte pela taxa de câmbio
            var converted = finalBrlForCurrency * rate.taxa

            // Arredondamento e formatação de acordo com regras de moedas sem decimal
            val isNoFraction = code == "PYG" || code == "ARS"
            val formatted = if (isNoFraction) {
                converted = Math.ceil(converted)
                String.format(Locale.US, "%.0f", converted)
            } else {
                String.format(Locale.US, "%.2f", converted)
            }
            json.put(code, formatted)
        }

        return json.toString()
    }
}
