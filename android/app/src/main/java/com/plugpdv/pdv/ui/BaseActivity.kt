package com.plugpdv.pdv.ui

import com.plugpdv.pdv.R
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.ForceLogoutBus
import com.plugpdv.pdv.utils.InAppUpdateManager
import com.plugpdv.pdv.utils.KillSwitchManager
import com.plugpdv.pdv.utils.LanguageManager
import com.plugpdv.pdv.utils.OutboxSyncManager
import com.plugpdv.pdv.utils.ServerStateEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
open class BaseActivity : AppCompatActivity() {

    @Inject
    lateinit var outboxSyncManager: OutboxSyncManager

    protected val inAppUpdateManager: InAppUpdateManager by lazy {
        InAppUpdateManager(this)
    }

    private var outboxAlertSnackbar: Snackbar? = null

    override fun attachBaseContext(newBase: Context) {
        val lang = LanguageManager.getLanguage(newBase)
        super.attachBaseContext(LanguageManager.updateResources(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Keep one windowing model on pre-Android 15 and Android 15+.
        // Android 15 enforces edge-to-edge for targetSdk >= 35; explicitly enabling
        // it here prevents the UI contract from changing only on newer OS versions.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        observeServerEvents()
        observeOutboxQueue()
        inAppUpdateManager.checkForUpdates(forceImmediate = false)
    }

    override fun onContentChanged() {
        super.onContentChanged()
        applyWindowInsets()
    }

    private fun applyWindowInsets() {
        val rootView = findViewById<android.view.View>(android.R.id.content) ?: return

        // Capture the layout's authored padding once for this content view. Insets
        // are always added to that baseline, avoiding cumulative padding when the
        // platform dispatches them more than once.
        val initialLeft = rootView.paddingLeft
        val initialTop = rootView.paddingTop
        val initialRight = rootView.paddingRight
        val initialBottom = rootView.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { view, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(
                initialLeft + safeArea.left,
                initialTop + safeArea.top,
                initialRight + safeArea.right,
                initialBottom + safeArea.bottom
            )
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    override fun onResume() {
        super.onResume()
        inAppUpdateManager.onResume()
    }

    override fun onDestroy() {
        super.onDestroy()
        inAppUpdateManager.unregister()
    }

    private fun observeServerEvents() {
        lifecycleScope.launch {
            ForceLogoutBus.serverStateEvents.collectLatest { event ->
                when (event) {
                    is ServerStateEvent.DeviceBlocked -> {
                        KillSwitchManager.forceLogout(applicationContext, event.reason)
                    }
                    is ServerStateEvent.DeviceNotRegistered -> {
                        KillSwitchManager.forceLogout(applicationContext, event.message)
                    }
                    is ServerStateEvent.DeviceIdRequiredBug -> {
                        Toast.makeText(this@BaseActivity, event.message, Toast.LENGTH_LONG).show()
                    }
                    is ServerStateEvent.UpgradeRequired -> {
                        inAppUpdateManager.checkForUpdates(forceImmediate = true)
                    }
                    is ServerStateEvent.ServiceUnavailableHiccup -> {
                        // Não bloqueia; delegado para sincronização via Outbox
                    }
                    is ServerStateEvent.ClockDivergenceDetected -> {
                        Toast.makeText(this@BaseActivity, event.message, Toast.LENGTH_LONG).show()
                    }
                    else -> {
                        // Degradação graciosa para futuros eventos
                    }
                }
            }
        }
    }

    private fun observeOutboxQueue() {
        lifecycleScope.launch {
            outboxSyncManager.queueStatus.collectLatest { status ->
                if (status.hasCriticalQueue && !status.alertMessage.isNullOrEmpty()) {
                    val rootView = findViewById<android.view.View>(android.R.id.content)
                    if (rootView != null) {
                        if (outboxAlertSnackbar == null || outboxAlertSnackbar?.isShownOrQueued == false) {
                            outboxAlertSnackbar = Snackbar.make(rootView, status.alertMessage, Snackbar.LENGTH_INDEFINITE)
                                .setAction(R.string.sync_action) {
                                    outboxSyncManager.triggerSync()
                                }
                            outboxAlertSnackbar?.show()
                        }
                    }
                } else {
                    outboxAlertSnackbar?.dismiss()
                }
            }
        }
        lifecycleScope.launch {
            outboxSyncManager.syncFeedback.collectLatest { feedback ->
                val message = when (feedback) {
                    "SYNCING" -> getString(R.string.sync_in_progress)
                    "SYNCED" -> getString(R.string.sync_completed)
                    "PENDING" -> getString(R.string.sync_still_pending)
                    "RECONCILIATION" -> getString(R.string.sync_requires_reconciliation)
                    else -> null
                }
                if (message != null) {
                    val rootView = findViewById<android.view.View>(android.R.id.content)
                    rootView?.let { Snackbar.make(it, message, Snackbar.LENGTH_SHORT).show() }
                }
            }
        }
    }

    fun showCurrencySelector(onCurrencyChanged: Runnable?) {
        val manager = CurrencyManager.getInstance()
        val items = if (manager.hasCapabilitiesAuthority()) {
            manager.getAuthorizedCurrencyCodes().toTypedArray()
        } else {
            manager.getAvailableCurrencies().map { it.codigo }.toTypedArray()
        }
        if (items.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle(R.string.currency_selector_title)
            .setItems(items) { _, which ->
                if (manager.selectAuthorizedCurrency(items[which])) onCurrencyChanged?.run()
            }
            .show()
    }
}
