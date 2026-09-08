package com.plugpdv.pdv

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.CatalogDao
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.repository.RestaurantOpsRepository
import com.plugpdv.pdv.ui.sale.CommandTotalDisplay
import com.plugpdv.pdv.ui.sale.CommandViewModel
import com.plugpdv.pdv.utils.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import retrofit2.Response
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class CommandTotalDisplayTest {
    private val rules = DefaultCurrencyRulesProvider()
    private val cm = CurrencyManager.getInstance()
    private val originalSelection = cm.selectedCurrency

    @Before fun setup() {
        rules.setCapabilities(mapOf(
            "PYG" to CurrencyCapability("PYG", "PYG", thousandsSeparator = ",", decimalSeparator = ".", displayDecimals = 0, minorUnitDigits = 0),
            "BRL" to CurrencyCapability("BRL", "R$", thousandsSeparator = ",", decimalSeparator = "."),
            "USD" to CurrencyCapability("USD", "USD", thousandsSeparator = ",", decimalSeparator = ".", displayDecimals = 0)
        ))
        cm.selectedCurrency = "PYG"
    }

    @After fun cleanup() { cm.setRates(null); cm.selectedCurrency = originalSelection }

    private fun detail(currency: String = "PYG", amount: String = "43384") = Gson().fromJson(
        """{"id":"c1","status":"ABERTA","total_comanda":$amount,"base_currency":"$currency","itens":[],"pagamentos":[]}""",
        ComandaDetailResponse::class.java
    )

    @Test fun canonicalPyg() { assertEquals("PYG 43,384", CommandTotalDisplay.format(detail(), rules)) }

    @Test fun configuredFxCannotMultiplyCanonicalPyg() {
        cm.setRates(ExchangeResponse("PYG", listOf(ExchangeResponse.CurrencyRate("PYG", 1160.0))))
        assertEquals(50325440.0, cm.convert(43384.0), 0.0) // Reproduce the old path.
        assertEquals("PYG 43,384", CommandTotalDisplay.format(detail(), rules))
    }

    @Test fun canonicalBrlIgnoresSelectedPyg() {
        assertEquals("R$ 100.00", CommandTotalDisplay.format(detail("BRL", "100.00"), rules))
    }

    @Test fun canonicalUsdIgnoresSelectedPyg() {
        assertEquals("USD 10", CommandTotalDisplay.format(detail("USD", "10"), rules))
    }

    @Test fun missingCurrencyIsNeverGuessed() {
        assertEquals("—", CommandTotalDisplay.format(detail().copy(baseCurrency = null), rules))
        assertEquals("—", CommandTotalDisplay.format(null, rules))
    }

    @Test fun capabilitiesOverrideDeviceLocale() {
        val original = Locale.getDefault()
        try {
            for (locale in listOf("pt-BR", "es", "en", "gn")) {
                Locale.setDefault(Locale.forLanguageTag(locale))
                assertEquals("PYG 43,384", CommandTotalDisplay.format(detail(), rules))
            }
        } finally { Locale.setDefault(original) }
    }

    @Test fun addItemRetainsCanonicalTotalUntilRefreshAndRefreshPreservesCurrency() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(Constants.TOKEN, "token").commit()
        val api = mock<PosApiService>()
        whenever(api.getComandaDetail(any(), any())).thenReturn(detail())
        val vm = CommandViewModel(api, mock<CatalogDao>(), context, mock<RestaurantOpsRepository>())
        whenever(api.manageComanda(any(), any(), anyOrNull())).thenAnswer {
            assertEquals(1, vm.items.value!!.size)
            assertEquals("PYG 43,384", CommandTotalDisplay.format(vm.comanda.value, rules))
            cm.selectedCurrency = "USD"
            Response.success(emptyMap<String, Any>())
        }
        vm.fetchComanda("token", "c1")
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("PYG 43,384", CommandTotalDisplay.format(vm.comanda.value, rules))
        // The API callback above checks the optimistic state before returning a response.
        vm.addItemToComanda("token", "c1", Product(id = "p1", name = "Item", selling_price = 18.0), 1, null)
        assertEquals("PYG 43,384", CommandTotalDisplay.format(vm.comanda.value, rules))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("PYG 43,384", CommandTotalDisplay.format(vm.comanda.value, rules))
        verify(api, times(2)).getComandaDetail(any(), any())
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        Unit
    }
}
