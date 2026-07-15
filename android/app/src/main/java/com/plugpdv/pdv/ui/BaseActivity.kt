package com.plugpdv.pdv.ui

import android.content.Context
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.ForceLogoutBus
import com.plugpdv.pdv.utils.KillSwitchManager
import com.plugpdv.pdv.utils.LanguageManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
open class BaseActivity : AppCompatActivity() {

    override fun attachBaseContext(newBase: Context) {
        val lang = LanguageManager.getLanguage(newBase)
        super.attachBaseContext(LanguageManager.updateResources(newBase, lang))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        observeKillSwitch()
    }

    private fun observeKillSwitch() {
        lifecycleScope.launch {
            ForceLogoutBus.events.collectLatest { reason ->
                KillSwitchManager.forceLogout(applicationContext, reason)
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
