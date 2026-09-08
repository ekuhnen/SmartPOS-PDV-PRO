package com.plugpdv.pdv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.TenantBindingStore
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
class CurrencyAuthorityAlignmentTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = CurrencyManager.getInstance()
    private val prefs = context.getSharedPreferences("currency_prefs", Context.MODE_PRIVATE)

    @Before fun setup() {
        prefs.edit().clear().commit()
        TenantBindingStore.clearTenant(context)
        TenantBindingStore.setActiveTenantId(context, "tenant-py")
        manager.prepareForTenant(context, "tenant-py")
    }
    @After fun cleanup() {
        prefs.edit().clear().commit()
        TenantBindingStore.clearTenant(context)
        manager.setRates(null)
        manager.selectedCurrency = "BRL"
    }

    private fun capabilities(vararg codes: String, base: String? = null) = CapabilitiesResponse(
        currencies = codes.associateWith { CurrencyCapability(it, it) }, baseCurrency = base
    )

    @Test fun pygUsdCapabilitiesAreSelectorAuthorityAndDoNotInventBrl() {
        manager.applyCapabilities(context, "tenant-py", capabilities("PYG", "USD", base = "PYG"))
        assertEquals(listOf("PYG", "USD"), manager.getAuthorizedCurrencyCodes())
        assertFalse(manager.getAuthorizedCurrencyCodes().contains("BRL"))
        assertTrue(manager.hasCapabilitiesAuthority())
    }

    @Test fun brlOnlyCapabilitiesExposeOnlyBrl() {
        manager.applyCapabilities(context, "tenant-py", capabilities("BRL", base = "BRL"))
        assertEquals(listOf("BRL"), manager.getAuthorizedCurrencyCodes())
    }

    @Test fun brlUsdCapabilitiesExposeBoth() {
        manager.applyCapabilities(context, "tenant-py", capabilities("BRL", "USD", base = "BRL"))
        assertEquals(listOf("BRL", "USD"), manager.getAuthorizedCurrencyCodes())
    }

    @Test fun basePygWinsOverLegacyInitialBrl() {
        manager.selectedCurrency = "BRL"
        manager.applyCapabilities(context, "tenant-py", capabilities("PYG", "USD", base = "PYG"))
        assertEquals("PYG", manager.getBaseCurrency())
        assertEquals("PYG", manager.selectedCurrency)
    }

    @Test fun staleBrlCacheIsReplacedByAuthenticatedCapabilities() {
        prefs.edit().putString("currency_owner_id", "tenant-py")
            .putStringSet("authorized_currency_codes", setOf("BRL"))
            .putString("authorized_base_currency", "BRL").commit()
        manager.prepareForTenant(context, "tenant-py")
        assertEquals(listOf("BRL"), manager.getAuthorizedCurrencyCodes())
        manager.applyCapabilities(context, "tenant-py", capabilities("PYG", "USD", base = "PYG"))
        assertEquals(listOf("PYG", "USD"), manager.getAuthorizedCurrencyCodes())
        assertFalse(manager.isAuthorized("BRL"))
    }

    @Test fun cacheFromDifferentTenantIsRejectedAndStateDropped() {
        manager.applyCapabilities(context, "tenant-a", capabilities("BRL", base = "BRL"))
        TenantBindingStore.setActiveTenantId(context, "tenant-b")
        manager.prepareForTenant(context, "tenant-b")
        assertTrue(manager.getAuthorizedCurrencyCodes().isEmpty())
        assertFalse(manager.isAuthorized("PYG")) // no authority means no false allow-list claim
        assertEquals("BRL", manager.selectedCurrency)
    }

    @Test fun ratesAreFxOnlyAndDoNotAuthorizeExtraCurrency() {
        manager.applyCapabilities(context, "tenant-py", capabilities("PYG", "USD", base = "PYG"))
        manager.setRates(ExchangeResponse("PYG", listOf(
            ExchangeResponse.CurrencyRate("PYG", 1160.0),
            ExchangeResponse.CurrencyRate("USD", 0.2),
            ExchangeResponse.CurrencyRate("BRL", 1.0)
        )))
        assertEquals(listOf("PYG", "USD"), manager.getAuthorizedCurrencyCodes())
        assertFalse(manager.isAuthorized("BRL"))
    }

    @Test fun missingUsdRateFailsClosedWhilePygRemainsOperational() {
        manager.applyCapabilities(context, "tenant-py", capabilities("PYG", "USD", base = "PYG"))
        manager.setRates(ExchangeResponse("PYG", listOf(ExchangeResponse.CurrencyRate("PYG", 1160.0))))
        assertTrue(manager.isOperational("PYG"))
        assertFalse(manager.isOperational("USD"))
    }

    @Test fun cashierRequestRetainsSelectedCurrencySemantics() {
        manager.applyCapabilities(context, "tenant-py", capabilities("PYG", "USD", base = "PYG"))
        val pyg = CashierRequest("abrir", BigDecimal("100"), "PYG")
        assertEquals("PYG", pyg.moeda)
        manager.selectedCurrency = "USD"
        val usd = CashierRequest("abrir", BigDecimal("10"), manager.selectedCurrency)
        assertEquals("USD", usd.moeda)
        assertEquals(BigDecimal("100"), pyg.valor)
    }

    @Test fun capabilityAuthoritySurvivesFxSyncFailure() {
        manager.applyCapabilities(context, "tenant-py", capabilities("PYG", "USD", base = "PYG"))
        manager.setRates(null)
        assertEquals(listOf("PYG", "USD"), manager.getAuthorizedCurrencyCodes())
        assertEquals("PYG", manager.getBaseCurrency())
        assertFalse(manager.getAuthorizedCurrencyCodes().contains("BRL"))
    }
}
