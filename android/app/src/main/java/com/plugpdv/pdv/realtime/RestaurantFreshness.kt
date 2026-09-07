package com.plugpdv.pdv.realtime

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.plugpdv.pdv.repository.TableReadRepository
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.TenantBindingStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One app-context subscription shared by lifecycle-bound restaurant readers. */
@Singleton
class RestaurantFreshness @Inject constructor(
    @ApplicationContext private val context: Context,
    transport: SupabaseRestaurantTransport,
    private val tables: TableReadRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val targets = MutableStateFlow<List<RestaurantReadTarget>>(emptyList())
    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    init {
        scope.launch {
            RestaurantInvalidationCoordinator(transport, refreshTables = { session ->
                if (currentSession() == session) tables.refreshTables(session.token)
            }).run(sessions(), connectivity(), targets)
        }
    }

    private fun currentSession(): RestaurantSession? {
        val token = prefs.getString(Constants.TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val user = prefs.getString(Constants.USER_ID, null)?.takeIf { it.isNotBlank() } ?: return null
        val owner = TenantBindingStore.getActiveTenantId(context)?.takeIf { it.isNotBlank() } ?: return null
        return RestaurantSession(owner, user, token)
    }

    private fun sessions() = callbackFlow {
        val tenantPrefs = context.getSharedPreferences(TenantBindingStore.PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(currentSession()) }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        tenantPrefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(currentSession())
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            tenantPrefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()

    private fun connectivity() = callbackFlow {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun publish() {
            val capabilities = manager.getNetworkCapabilities(manager.activeNetwork)
            trySend(capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = publish()
            override fun onLost(network: Network) = publish()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = publish()
        }
        manager.registerDefaultNetworkCallback(callback)
        publish()
        awaitClose { manager.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()

    /** Call inside repeatOnLifecycle. Cancellation removes the reader, never the Room cache. */
    suspend fun whileVisible(refreshDetail: (suspend (String) -> Unit)? = null) {
        val session = currentSession() ?: return
        val target = RestaurantReadTarget(session.ownerId, session.userId) { active ->
            // Check the actual preferences as well as the observed session (logout callbacks can lag).
            if (currentSession() == active) {
                refreshDetail?.invoke(active.token)
            }
        }
        targets.update { it + target }
        try {
            awaitCancellation()
        } finally {
            targets.update { it - target }
        }
    }
}
