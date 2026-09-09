package com.plugpdv.pdv

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.plugpdv.pdv.models.CapabilitiesResponse
import com.plugpdv.pdv.models.CurrencyCapability
import com.plugpdv.pdv.models.PaymentProviderCapability
import com.plugpdv.pdv.utils.PaymentProviderCapabilitiesStore
import com.plugpdv.pdv.utils.PaymentProviderType
import com.plugpdv.pdv.utils.TenantBindingStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PaymentProviderCapabilitiesStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val store = PaymentProviderCapabilitiesStore.getInstance()
    private val prefs = context.getSharedPreferences("payment_provider_capabilities_prefs", Context.MODE_PRIVATE)

    @Before
    fun setup() {
        prefs.edit().clear().commit()
        TenantBindingStore.clearTenant(context)
        TenantBindingStore.setActiveTenantId(context, "tenant-a")
        store.prepareForTenant(context, "tenant-a")
        store.applyCapabilities(context, "tenant-a", capabilities(providers = null))
    }

    @After
    fun cleanup() {
        prefs.edit().clear().commit()
        TenantBindingStore.clearTenant(context)
        TenantBindingStore.setActiveTenantId(context, "test-reset-tenant")
        store.prepareForTenant(context, "test-reset-tenant")
        TenantBindingStore.clearTenant(context)
    }

    private fun capabilities(
        providers: List<PaymentProviderCapability>?,
        defaultProvider: String? = null,
        allowSelection: Boolean? = null
    ) = CapabilitiesResponse(
        currencies = mapOf("BRL" to CurrencyCapability("BRL", "R$")),
        baseCurrency = "BRL",
        paymentProviders = providers,
        defaultPaymentProvider = defaultProvider,
        allowPaymentProviderSelection = allowSelection
    )

    @Test
    fun missingProviderConfigKeepsLegacyPlugPayCompatibility() {
        store.applyCapabilities(context, "tenant-a", capabilities(providers = null))

        val policy = store.resolvePolicy("BRL")
        assertFalse(store.hasExplicitAuthority())
        assertTrue(policy.legacyFallback)
        assertEquals(listOf(PaymentProviderType.PLUGPAY), policy.enabledProviders)
        assertEquals(PaymentProviderType.PLUGPAY, policy.defaultProvider)
        assertFalse(policy.allowOperatorSelection)
    }

    @Test
    fun explicitPlugPayOnlyUsesServerAuthority() {
        store.applyCapabilities(
            context,
            "tenant-a",
            capabilities(
                providers = listOf(
                    PaymentProviderCapability("PLUGPAY", enabled = true, supportedCurrencies = listOf("BRL", "PYG"))
                ),
                defaultProvider = "PLUGPAY"
            )
        )

        val policy = store.resolvePolicy("PYG")
        assertTrue(store.hasExplicitAuthority())
        assertFalse(policy.legacyFallback)
        assertEquals(listOf(PaymentProviderType.PLUGPAY), policy.enabledProviders)
        assertEquals(PaymentProviderType.PLUGPAY, policy.defaultProvider)
    }

    @Test
    fun cieloCanBeAuthorizedOnlyForBrl() {
        store.applyCapabilities(
            context,
            "tenant-a",
            capabilities(
                providers = listOf(
                    PaymentProviderCapability("CIELO_TAP", enabled = true, supportedCurrencies = listOf("BRL"))
                ),
                defaultProvider = "CIELO_TAP"
            )
        )

        val brl = store.resolvePolicy("BRL")
        assertEquals(listOf(PaymentProviderType.CIELO_TAP), brl.enabledProviders)
        assertEquals(PaymentProviderType.CIELO_TAP, brl.defaultProvider)

        val pyg = store.resolvePolicy("PYG")
        assertTrue(pyg.enabledProviders.isEmpty())
        assertNull(pyg.defaultProvider)
        assertFalse(pyg.legacyFallback)
    }

    @Test
    fun bothProvidersCanAllowOperatorSelection() {
        store.applyCapabilities(
            context,
            "tenant-a",
            capabilities(
                providers = listOf(
                    PaymentProviderCapability("PLUGPAY", enabled = true, supportedCurrencies = listOf("BRL")),
                    PaymentProviderCapability("CIELO_TAP", enabled = true, supportedCurrencies = listOf("BRL"))
                ),
                defaultProvider = "CIELO_TAP",
                allowSelection = true
            )
        )

        val policy = store.resolvePolicy("BRL")
        assertEquals(listOf(PaymentProviderType.PLUGPAY, PaymentProviderType.CIELO_TAP), policy.enabledProviders)
        assertEquals(PaymentProviderType.CIELO_TAP, policy.defaultProvider)
        assertTrue(policy.allowOperatorSelection)
    }

    @Test
    fun explicitEmptyProviderListDoesNotReEnableLegacyPlugPay() {
        store.applyCapabilities(context, "tenant-a", capabilities(providers = emptyList()))

        val policy = store.resolvePolicy("BRL")
        assertTrue(store.hasExplicitAuthority())
        assertTrue(policy.enabledProviders.isEmpty())
        assertNull(policy.defaultProvider)
        assertFalse(policy.legacyFallback)
    }

    @Test
    fun unknownProviderCodesDegradeGracefully() {
        store.applyCapabilities(
            context,
            "tenant-a",
            capabilities(
                providers = listOf(
                    PaymentProviderCapability("FUTURE_PROVIDER", enabled = true, supportedCurrencies = listOf("BRL")),
                    PaymentProviderCapability("PLUGPAY", enabled = true, supportedCurrencies = listOf("BRL"))
                ),
                defaultProvider = "FUTURE_PROVIDER",
                allowSelection = true
            )
        )

        val policy = store.resolvePolicy("BRL")
        assertEquals(listOf(PaymentProviderType.PLUGPAY), policy.enabledProviders)
        assertNull(policy.defaultProvider)
        assertTrue(policy.allowOperatorSelection)
    }

    @Test
    fun providerCacheIsTenantScoped() {
        store.applyCapabilities(
            context,
            "tenant-a",
            capabilities(
                providers = listOf(PaymentProviderCapability("CIELO_TAP", enabled = true)),
                defaultProvider = "CIELO_TAP"
            )
        )
        assertEquals(listOf(PaymentProviderType.CIELO_TAP), store.resolvePolicy("BRL").enabledProviders)

        TenantBindingStore.setActiveTenantId(context, "tenant-b")
        store.prepareForTenant(context, "tenant-b")
        assertEquals(listOf(PaymentProviderType.PLUGPAY), store.resolvePolicy("BRL").enabledProviders)
        assertTrue(store.resolvePolicy("BRL").legacyFallback)

        TenantBindingStore.setActiveTenantId(context, "tenant-a")
        store.prepareForTenant(context, "tenant-a")
        assertEquals(listOf(PaymentProviderType.CIELO_TAP), store.resolvePolicy("BRL").enabledProviders)
        assertFalse(store.resolvePolicy("BRL").legacyFallback)
    }

    @Test
    fun successfulLegacyCapabilitiesResponseClearsStaleExplicitPolicy() {
        store.applyCapabilities(
            context,
            "tenant-a",
            capabilities(
                providers = listOf(PaymentProviderCapability("CIELO_TAP", enabled = true)),
                defaultProvider = "CIELO_TAP"
            )
        )
        assertTrue(store.hasExplicitAuthority())

        store.applyCapabilities(context, "tenant-a", capabilities(providers = null))

        val policy = store.resolvePolicy("BRL")
        assertFalse(store.hasExplicitAuthority())
        assertTrue(policy.legacyFallback)
        assertEquals(listOf(PaymentProviderType.PLUGPAY), policy.enabledProviders)
    }

    @Test
    fun gsonPreservesAbsentVersusExplicitEmptyProviderList() {
        val gson = Gson()
        val absent = gson.fromJson("{\"currencies\":{}}", CapabilitiesResponse::class.java)
        val explicitEmpty = gson.fromJson("{\"currencies\":{},\"payment_providers\":[]}", CapabilitiesResponse::class.java)

        assertNull(absent.paymentProviders)
        assertTrue(explicitEmpty.paymentProviders != null)
        assertTrue(explicitEmpty.paymentProviders!!.isEmpty())
    }
}
