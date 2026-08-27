package com.plugpdv.pdv.ui

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.ForceLogoutBus
import com.plugpdv.pdv.utils.InAppUpdateManager
import com.plugpdv.pdv.utils.KillSwitchManager
import com.plugpdv.pdv.utils.LanguageManager
import com.plugpdv.pdv.utils.OutboxSyncManager
import com.plugpdv.pdv.utils.ServerStateEvent
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
        val rootView = findViewById<android.view.View>(android.R.id.content)
        rootView?.let { view ->
            ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
                val systemBars = insets.getInsets(
                    WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout()
                )
                v.setPadding(
                    v.paddingLeft,
                    systemBars.top,
                    v.paddingRight,
                    v.paddingBottom
                )
                insets
            }
            ViewCompat.requestApplyInsets(view)
        }
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
                                .setAction("Sincronizar") {
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
    }

    fun showCurrencySelector(onCurrencyChanged: Runnable?) {
        val currencies = CurrencyManager.getInstance().getAvailableCurrencies()
        if (currencies.isEmpty()) return

        val items = currencies.map { it.codigo }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Selecionar Moeda / Select Currency")
            .setItems(items) { _, which ->
                CurrencyManager.getInstance().selectedCurrency = items[which]
                onCurrencyChanged?.run()
            }
            .show()
    }
}
