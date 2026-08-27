package com.plugpdv.pdv

import com.plugpdv.pdv.database.TaxEntity
import com.plugpdv.pdv.models.CurrencyCapability
import com.plugpdv.pdv.models.ExchangeResponse
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider
import org.junit.Before
import org.junit.Test
import java.util.Locale

class CurrencyExchangeAllTests {

    private lateinit var currencyManager: CurrencyManager
    private lateinit var rulesProvider: DefaultCurrencyRulesProvider

    @Before
    fun setUp() {
        currencyManager = CurrencyManager.getInstance()
        rulesProvider = DefaultCurrencyRulesProvider()

        // Simula os dados de câmbio recebidos da API
        val mockApiRates = ExchangeResponse(
            moeda_principal = "BRL",
            moedas = listOf(
                ExchangeResponse.CurrencyRate("BRL", 1.0),
                ExchangeResponse.CurrencyRate("PYG", 1189.82),
                ExchangeResponse.CurrencyRate("USD", 0.20),
                ExchangeResponse.CurrencyRate("EUR", 0.18),
                ExchangeResponse.CurrencyRate("ARS", 180.0),
                ExchangeResponse.CurrencyRate("DOB", 10.5)
            )
        )
        currencyManager.setRates(mockApiRates)

        // Configura as capabilities padrão para formatação
        val capabilities = mapOf(
            "BRL" to CurrencyCapability("BRL", "R$", "PREFIX", ".", ",", 2),
            "USD" to CurrencyCapability("USD", "$", "PREFIX", ",", ".", 2),
            "PYG" to CurrencyCapability("PYG", "Gs.", "PREFIX", ".", ",", 0),
            "EUR" to CurrencyCapability("EUR", "€", "SUFFIX", ".", ",", 2),
            "ARS" to CurrencyCapability("ARS", "$", "PREFIX", ".", ",", 0),
            "DOB" to CurrencyCapability("DOB", "RD$", "PREFIX", ",", ".", 2)
        )
        rulesProvider.setCapabilities(capabilities)
    }

    @Test
    fun test1_Convert1500PygToAllCurrencies() {
        println("==================================================")
        println("TESTE 1: CONVERSÃO DE 1500 PYG PARA TODAS AS MOEDAS")
        println("==================================================")

        val amountPyg = 1500.0
        val amountBrl = currencyManager.toBrl(amountPyg, "PYG")

        println("Valor Original: 1500 PYG")
        println("-> Convertido para BRL (base): $amountBrl BRL | Formatado: ${rulesProvider.formatExplicit(amountBrl, "BRL")}")

        val targetCurrencies = listOf("BRL", "USD", "EUR", "ARS", "DOB")
        for (target in targetCurrencies) {
            val converted = currencyManager.fromBrl(amountBrl, target)
            val formatted = rulesProvider.formatExplicit(converted, target)
            println("-> 1500 PYG para $target: $converted | Formatado: $formatted")
        }
    }

    @Test
    fun test2_ConvertAllCurrenciesToPygAndOthers() {
        println("\n==================================================")
        println("TESTE 2: CONVERSÃO INVERSA (1500 DE CADA MOEDA PARA PYG E OUTRAS)")
        println("==================================================")

        val currencies = listOf("BRL", "USD", "EUR", "ARS", "DOB")
        val amount = 1500.0

        for (fromCurrency in currencies) {
            println("\n--- Moeda Origem: 1500 $fromCurrency ---")
            val amountBrl = currencyManager.toBrl(amount, fromCurrency)
            println("Equivalent BRL: $amountBrl BRL")

            val targets = listOf("PYG", "BRL", "USD", "EUR", "ARS", "DOB").filter { it != fromCurrency }
            for (target in targets) {
                val converted = currencyManager.fromBrl(amountBrl, target)
                val formatted = rulesProvider.formatExplicit(converted, target)
                println("   1500 $fromCurrency -> $target = $converted | Formatado: $formatted")
            }
        }
    }

    @Test
    fun test3_PaymentHelperAmountsJsonCalculation() {
        println("\n==================================================")
        println("TESTE 3: CÁLCULO MULTIMOEDA DO PaymentHelper.generateAmountsJson")
        println("==================================================")

        fun createTax(idStr: String, nameStr: String, pct: Double, curr: String) = TaxEntity().apply {
            id = idStr
            name = nameStr
            percentage = pct
            currency = curr
            active = true
        }

        val activeTaxes = listOf(
            createTax("1", "IVA BR", 10.0, "BRL"),
            createTax("2", "IVA PY", 10.0, "PYG"),
            createTax("3", "Tax US", 7.0, "USD"),
            createTax("4", "Tax EU", 20.0, "EUR"),
            createTax("5", "IVA AR", 21.0, "ARS")
        )

        // Função de simulação pura do algoritmo do PaymentHelper (para evitar a stube da org.json.JSONObject no JUnit JVM)
        fun calculateAmountsMap(
            inputAmountBrl: Double,
            selectedCurrency: String,
            taxes: List<TaxEntity>
        ): Map<String, String> {
            val resultMap = mutableMapOf<String, String>()
            val selectedTaxPercent = taxes.filter { it.currency.equals(selectedCurrency, ignoreCase = true) }.sumOf { it.percentage }
            val baseBrl = inputAmountBrl / (1.0 + (selectedTaxPercent / 100.0))

            val brlTaxPercent = taxes.filter { it.currency.equals("BRL", ignoreCase = true) }.sumOf { it.percentage }
            val finalBrl = baseBrl * (1.0 + (brlTaxPercent / 100.0))
            resultMap["BRL"] = String.format(Locale.US, "%.2f", finalBrl)

            currencyManager.getAvailableCurrencies().forEach { rate ->
                val code = rate.codigo.uppercase()
                val taxPercent = taxes.filter { it.currency.equals(code, ignoreCase = true) }.sumOf { it.percentage }
                val finalBrlForCurrency = baseBrl * (1.0 + (taxPercent / 100.0))
                var converted = finalBrlForCurrency * rate.taxa

                val isNoFraction = code == "PYG" || code == "ARS"
                val formatted = if (isNoFraction) {
                    converted = Math.ceil(converted)
                    String.format(Locale.US, "%.0f", converted)
                } else {
                    String.format(Locale.US, "%.2f", converted)
                }
                resultMap[code] = formatted
            }
            return resultMap
        }

        val inputAmountBrl = 100.0 // 100 BRL
        val mapResult = calculateAmountsMap(inputAmountBrl, "BRL", activeTaxes)
        println("Input: 100.00 BRL (com imposto BRL de 10%)")
        println("Valores gerados por moeda (PaymentHelper): $mapResult")

        // Teste com produto de 1500 PYG informado no Checkout
        val pygAmountInBrl = currencyManager.toBrl(1500.0, "PYG")
        val mapPygResult = calculateAmountsMap(pygAmountInBrl, "PYG", activeTaxes)
        println("\nInput: 1500 PYG (convertido para base BRL $pygAmountInBrl, com imposto PYG de 10%)")
        println("Valores gerados por moeda (PaymentHelper): $mapPygResult")
    }

    @Test
    fun test4_FormattingAndKeypadInput() {
        println("\n==================================================")
        println("TESTE 4: FORMATAÇÃO E ENTRADA DE TECLADO (Keypad/MinorUnits)")
        println("==================================================")

        val testCases = listOf(
            Pair(1500L, "BRL"),
            Pair(1500L, "USD"),
            Pair(1500L, "PYG"),
            Pair(1500L, "EUR"),
            Pair(1500L, "ARS"),
            Pair(1500L, "DOB")
        )

        println("Formatação por Unidade Mínima (Minor Units = 1500):")
        for ((minorUnits, code) in testCases) {
            val formatted = rulesProvider.formatMinorUnits(minorUnits, code)
            println("   MinorUnits: $minorUnits | Moeda: $code -> Formatado: '$formatted'")
        }

        println("\nSimulação Digitação Teclado '1500':")
        for ((_, code) in testCases) {
            val formattedInput = rulesProvider.formatKeypadInput("1500", code)
            println("   Teclado: '1500' | Moeda: $code -> Exibido: '$formattedInput'")
        }
    }
}
