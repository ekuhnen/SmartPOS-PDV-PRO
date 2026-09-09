package com.plugpdv.pdv.utils

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.plugpdv.pdv.models.CapabilitiesResponse
import com.plugpdv.pdv.models.PaymentProviderCapability

/**
 * Provider identifiers implemented by this APK.
 *
 * Keep the wire value as String in CapabilitiesResponse. Unknown server values
 * must degrade gracefully instead of making Gson fail when the backend adds a
 * provider before this APK knows how to execute it.
 */
enum class PaymentProviderType {
    PLUGPAY,
    CIELO_TAP;

    companion object {
        fun fromWire(value: String?): PaymentProviderType? {
            val normalized = value?.trim()?.uppercase()?.takeIf { it.isNotEmpty() } ?: return null
            return entries.firstOrNull { it.name == normalized }
        }
    }
}

data class PaymentProviderPolicy(
    val enabledProviders: List<PaymentProviderType>,
    val defaultProvider: PaymentProviderType?,
    val allowOperatorSelection: Boolean,
    val legacyFallback: Boolean
) {
    fun isEnabled(provider: PaymentProviderType): Boolean = provider in enabledProviders
}

/**
 * Tenant-scoped cache of the non-secret payment-provider policy received in
 * terminal capabilities.
 *
 * The API remains the authority. This store only lets an already-authorized
 * policy survive process death/offline operation for the same tenant.
 *
 * Compatibility rule:
 * - payment_providers ABSENT: legacy APK/backend contract -> PlugPay fallback.
 * - payment_providers PRESENT (even empty): explicit server authority; never
 *   invent PlugPay locally.
 */
class PaymentProviderCapabilitiesStore private constructor() {
    private val gson = Gson()

    private var ownerId: String? = null
    private var explicitAuthority: Boolean = false
    private var providerCapabilities: List<PaymentProviderCapability> = emptyList()
    private var defaultProviderRaw: String? = null
    private var allowOperatorSelection: Boolean = false

    @Synchronized
    fun init(context: Context) {
        val activeTenant = TenantBindingStore.getActiveTenantId(context) ?: return
        prepareForTenant(context, activeTenant)
    }

    @Synchronized
    fun prepareForTenant(context: Context, ownerId: String) {
        require(ownerId.isNotBlank())

        if (this.ownerId != null && this.ownerId != ownerId) {
            resetInMemory()
        }
        this.ownerId = ownerId

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cachedOwner = prefs.getString(KEY_OWNER_ID, null)
        if (cachedOwner != ownerId) {
            explicitAuthority = false
            providerCapabilities = emptyList()
            defaultProviderRaw = null
            allowOperatorSelection = false
            return
        }

        explicitAuthority = prefs.getBoolean(KEY_EXPLICIT_AUTHORITY, false)
        if (!explicitAuthority) {
            providerCapabilities = emptyList()
            defaultProviderRaw = null
            allowOperatorSelection = false
            return
        }

        providerCapabilities = runCatching {
            val json = prefs.getString(KEY_PROVIDERS_JSON, null) ?: return@runCatching emptyList()
            val type = object : TypeToken<List<PaymentProviderCapability>>() {}.type
            gson.fromJson<List<PaymentProviderCapability>>(json, type).orEmpty()
        }.getOrElse {
            // The cache is not authority. Corrupt explicit authority fails closed
            // rather than silently re-enabling the legacy PlugPay fallback.
            emptyList()
        }
        defaultProviderRaw = prefs.getString(KEY_DEFAULT_PROVIDER, null)
        allowOperatorSelection = prefs.getBoolean(KEY_ALLOW_OPERATOR_SELECTION, false)
    }

    @Synchronized
    fun applyCapabilities(context: Context, ownerId: String, capabilities: CapabilitiesResponse) {
        require(ownerId.isNotBlank())
        prepareForTenant(context, ownerId)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val providers = capabilities.paymentProviders

        if (providers == null) {
            // Successful response from a legacy capabilities contract. Preserve
            // existing PlugPay behaviour but remove any stale explicit policy.
            explicitAuthority = false
            providerCapabilities = emptyList()
            defaultProviderRaw = null
            allowOperatorSelection = false
            prefs.edit()
                .putString(KEY_OWNER_ID, ownerId)
                .putBoolean(KEY_EXPLICIT_AUTHORITY, false)
                .remove(KEY_PROVIDERS_JSON)
                .remove(KEY_DEFAULT_PROVIDER)
                .remove(KEY_ALLOW_OPERATOR_SELECTION)
                .commit()
            return
        }

        explicitAuthority = true
        providerCapabilities = providers.toList()
        defaultProviderRaw = capabilities.defaultPaymentProvider?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        allowOperatorSelection = capabilities.allowPaymentProviderSelection == true

        prefs.edit()
            .putString(KEY_OWNER_ID, ownerId)
            .putBoolean(KEY_EXPLICIT_AUTHORITY, true)
            .putString(KEY_PROVIDERS_JSON, gson.toJson(providerCapabilities))
            .putString(KEY_DEFAULT_PROVIDER, defaultProviderRaw)
            .putBoolean(KEY_ALLOW_OPERATOR_SELECTION, allowOperatorSelection)
            .commit()
    }

    @Synchronized
    fun hasExplicitAuthority(): Boolean = explicitAuthority

    /**
     * Resolves only server-published policy plus the documented legacy fallback.
     * Device checks (Android version/NFC/SDK readiness) are a separate execution
     * capability gate and will be composed by PaymentCoordinator.
     */
    @Synchronized
    fun resolvePolicy(currencyCode: String? = null): PaymentProviderPolicy {
        if (!explicitAuthority) {
            return PaymentProviderPolicy(
                enabledProviders = listOf(PaymentProviderType.PLUGPAY),
                defaultProvider = PaymentProviderType.PLUGPAY,
                allowOperatorSelection = false,
                legacyFallback = true
            )
        }

        val normalizedCurrency = currencyCode?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        val enabled = providerCapabilities.asSequence()
            .filter { it.enabled }
            .filter { capability ->
                normalizedCurrency == null || capability.supportedCurrencies == null ||
                    capability.supportedCurrencies.any { it.trim().uppercase() == normalizedCurrency }
            }
            .mapNotNull { PaymentProviderType.fromWire(it.provider) }
            .distinct()
            .toList()

        val defaultProvider = PaymentProviderType.fromWire(defaultProviderRaw)
            ?.takeIf { it in enabled }

        return PaymentProviderPolicy(
            enabledProviders = enabled,
            defaultProvider = defaultProvider,
            allowOperatorSelection = allowOperatorSelection,
            legacyFallback = false
        )
    }

    private fun resetInMemory() {
        ownerId = null
        explicitAuthority = false
        providerCapabilities = emptyList()
        defaultProviderRaw = null
        allowOperatorSelection = false
    }

    companion object {
        private const val PREFS_NAME = "payment_provider_capabilities_prefs"
        private const val KEY_OWNER_ID = "owner_id"
        private const val KEY_EXPLICIT_AUTHORITY = "explicit_authority"
        private const val KEY_PROVIDERS_JSON = "providers_json"
        private const val KEY_DEFAULT_PROVIDER = "default_provider"
        private const val KEY_ALLOW_OPERATOR_SELECTION = "allow_operator_selection"

        @Volatile
        private var instance: PaymentProviderCapabilitiesStore? = null

        @JvmStatic
        fun getInstance(): PaymentProviderCapabilitiesStore {
            return instance ?: synchronized(this) {
                instance ?: PaymentProviderCapabilitiesStore().also { instance = it }
            }
        }
    }
}
