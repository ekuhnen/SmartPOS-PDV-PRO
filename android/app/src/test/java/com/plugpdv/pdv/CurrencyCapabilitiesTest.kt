package com.plugpdv.pdv

import com.plugpdv.pdv.models.CurrencyCapability
import com.plugpdv.pdv.utils.DefaultCurrencyRulesProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class CurrencyCapabilitiesTest {

    private lateinit var rulesProvider: DefaultCurrencyRulesProvider

    @Before
    fun setUp() {
        rulesProvider = DefaultCurrencyRulesProvider()
    }

    @Test
    fun testBrlFormatting_Prefix_TwoDecimals() {
        // 15.00 BRL (1500 minor units) -> "R$ 15,00"
        val formatted = rulesProvider.formatMinorUnits(1500L, "BRL")
        assertEquals("R$ 15,00", formatted)
    }

    @Test
    fun testPygFormatting_Prefix_ZeroDecimals() {
        // 150.000 PYG (150000 minor units) -> "Gs. 150.000"
        val formatted = rulesProvider.formatMinorUnits(150000L, "PYG")
        assertEquals("Gs. 150.000", formatted)
    }

    @Test
    fun testEurFormatting_Suffix_TwoDecimals() {
        // 12.50 EUR -> "12,50 €"
        val formatted = rulesProvider.formatMinorUnits(1250L, "EUR")
        assertEquals("12,50 €", formatted)
    }

    @Test
    fun testKeypadInput_Brl_TwoDecimals() {
        // Digita "1500" no teclado numérico -> "R$ 15,00"
        val formatted = rulesProvider.formatKeypadInput("1500", "BRL")
        assertEquals("R$ 15,00", formatted)
    }

    @Test
    fun testKeypadInput_Pyg_ZeroDecimals() {
        // Digita "150000" no teclado numérico -> "Gs. 150.000"
        val formatted = rulesProvider.formatKeypadInput("150000", "PYG")
        assertEquals("Gs. 150.000", formatted)
    }

    @Test
    fun testCustomCapabilitiesOverride() {
        val customCap = mapOf(
            "CLP" to CurrencyCapability(
                currencyCode = "CLP",
                symbol = "CLP$",
                symbolPosition = "PREFIX",
                thousandsSeparator = ".",
                decimalSeparator = ",",
                displayDecimals = 0
            )
        )
        rulesProvider.setCapabilities(customCap)

        val formatted = rulesProvider.formatMinorUnits(5000L, "CLP")
        assertEquals("CLP$ 5.000", formatted)
    }
}
