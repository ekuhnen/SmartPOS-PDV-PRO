package com.plugpdv.pdv.utils

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.ui.auth.LoginActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

object KillSwitchManager {

    private val logoutInProgress = AtomicBoolean(false)

    fun forceLogout(context: Context, reason: String) {
        if (!logoutInProgress.compareAndSet(false, true)) return

        CoroutineScope(Dispatchers.Main).launch {
            // 1. Limpar dados locais
            clearAllLocalData(context)

            // 2. Limpar Device ID (atacante não reutiliza)
            DeviceIdProvider.clear(context)

            // 3. Redirecionar para Login com banner de bloqueio
            val intent = Intent(context, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                putExtra(EXTRA_BLOCKED_REASON, reason)
            }
            context.startActivity(intent)
        }
    }

    private fun clearAllLocalData(context: Context) {
        // Limpar SharedPreferences de sessão
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(Constants.TOKEN)
            .remove(Constants.SESSION_ID)
            .remove(Constants.HAS_MESA)
            .remove(Constants.HAS_VENDA_DIRETA)
            .remove(Constants.HAS_COMANDA)
            .remove(Constants.EMAIL)
            .remove(Constants.PASSWORD)
            .remove(Constants.USER_ID)
            .remove(Constants.LOGIN_TIME)
            .apply()

        CashierAuthorityStore.clearAuthority(context)

        // Limpar banco de dados local
        val db = AppDatabase.getDatabase(context)
        CoroutineScope(Dispatchers.IO).launch {
            db.clearAllTables()
        }
    }

    fun reset() {
        logoutInProgress.set(false)
    }

    const val EXTRA_BLOCKED_REASON = "BLOCKED_REASON"
}
