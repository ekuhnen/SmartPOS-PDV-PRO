package com.plugpdv.pdv.ui.auth

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.viewModels
import com.plugpdv.pdv.R
import com.plugpdv.pdv.databinding.ActivityLoginBinding
import com.plugpdv.pdv.ui.BaseActivity
import com.plugpdv.pdv.ui.cashier.CashierActivity
import com.plugpdv.pdv.ui.sale.DirectSaleActivity
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.KillSwitchManager
import com.plugpdv.pdv.utils.LanguageManager
import dagger.hilt.android.AndroidEntryPoint
import com.google.firebase.messaging.FirebaseMessaging
import android.content.ClipboardManager
import android.content.ClipData
import android.util.Log
import com.plugpdv.pdv.service.DeviceGuardService
import com.plugpdv.pdv.utils.DeviceIdProvider
import javax.inject.Inject

@AndroidEntryPoint
class LoginActivity : BaseActivity() {
    private lateinit var binding: ActivityLoginBinding
    private val viewModel: AuthViewModel by viewModels()

    @Inject
    lateinit var deviceGuardService: DeviceGuardService

    @Inject
    lateinit var saleSyncScheduler: com.plugpdv.pdv.outbox.SaleSyncScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        var hasBlockReason = false
        // Exibir banner de bloqueio se vier de um force_logout
        intent.getStringExtra(KillSwitchManager.EXTRA_BLOCKED_REASON)?.let { reason ->
            showBlockedBanner(reason)
            KillSwitchManager.reset()
            hasBlockReason = true
        }

        // Obtém o FCM Token apenas para fins de registro e log
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                Log.d("FCM_TOKEN", "Token: $token")
            } else {
                val exception = task.exception
                Log.e("FCM_ERROR", "Falha ao obter token", exception)
            }
        }

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString()
            val password = binding.etPassword.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            viewModel.login(email, password)
        }

        binding.btnLangPt.setOnClickListener { changeLanguage("pt") }
        binding.btnLangEn.setOnClickListener { changeLanguage("en") }
        binding.btnLangEs.setOnClickListener { changeLanguage("es") }

        observeViewModel()

        if (!hasBlockReason) {
            checkAutoLogin()
        }
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(this) { loading ->
            binding.btnLogin.isEnabled = !loading
            // Only hide if we are NOT in a successful login transition
            if (!loading && viewModel.loginResult.value !is LoginResult.Success) {
                binding.loadingLayout.loadingOverlay.visibility = View.GONE
            } else if (loading) {
                binding.loadingLayout.loadingOverlay.visibility = View.VISIBLE
            }
        }

        viewModel.loginResult.observe(this) { result ->
            when (result) {
                is LoginResult.Success -> {
                    val email = binding.etEmail.text.toString()
                    val password = binding.etPassword.text.toString()
                    
                    val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
                    val currentLoginTime = prefs.getLong(Constants.LOGIN_TIME, 0L)
                    
                    val editor = prefs.edit()
                        .putString(Constants.TOKEN, result.token)
                        .putString(Constants.EMAIL, email)
                        .putString(Constants.PASSWORD, password)
                        .putBoolean(Constants.HAS_MESA, result.hasMesa)
                        .putBoolean(Constants.HAS_VENDA_DIRETA, result.hasVendaDireta)
                        .putBoolean(Constants.HAS_COMANDA, result.hasComanda)
                        .putString(Constants.USER_ID, result.userId)
                    
                    if (currentLoginTime <= 0L) {
                        editor.putLong(Constants.LOGIN_TIME, System.currentTimeMillis())
                    }
                    
                    if (result.isOpen && !result.sessionId.isNullOrEmpty()) {
                        editor.putString(Constants.SESSION_ID, result.sessionId)
                    } else {
                        editor.remove(Constants.SESSION_ID)
                    }
                    editor.apply()
                        
                    // Iniciar monitoramento Realtime (Kill-Switch)
                    deviceGuardService.start(this, result.userId, DeviceIdProvider.get(this))

                    // Reagendar WorkManager imediatamente para processar outbox suspensa por falta de token
                    saleSyncScheduler.scheduleSync(this)
                        
                    if (result.isOpen) {
                        val intent = Intent(this, DirectSaleActivity::class.java).apply {
                            putExtra("ACCESS_TOKEN", result.token)
                        }
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this, R.string.cashier_closed_msg, Toast.LENGTH_LONG).show()
                        val intent = Intent(this, CashierActivity::class.java).apply {
                            putExtra("ACCESS_TOKEN", result.token)
                        }
                        startActivity(intent)
                        finish()
                    }
                }
                is LoginResult.Error -> {
                    Toast.makeText(this, result.message, Toast.LENGTH_LONG).show()
                }
                null -> {}
            }
        }
    }

    private fun changeLanguage(lang: String) {
        LanguageManager.setLanguage(this, lang)
        recreate()
    }

    private fun checkAutoLogin() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val email = prefs.getString(Constants.EMAIL, null)
        val password = prefs.getString(Constants.PASSWORD, null)
        val loginTime = prefs.getLong(Constants.LOGIN_TIME, 0L)

        if (!email.isNullOrEmpty() && !password.isNullOrEmpty() && loginTime > 0L) {
            val currentTime = System.currentTimeMillis()
            val elapsed = currentTime - loginTime
            val eightHoursMs = 8 * 60 * 60 * 1000L

            if (elapsed in 0..eightHoursMs) {
                binding.etEmail.setText(email)
                binding.etPassword.setText(password)
                viewModel.login(email, password)
            } else {
                clearSavedCredentials()
            }
        }
    }

    private fun clearSavedCredentials() {
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(Constants.TOKEN)
            .remove(Constants.EMAIL)
            .remove(Constants.PASSWORD)
            .remove(Constants.SESSION_ID)
            .remove(Constants.LOGIN_TIME)
            .remove(Constants.USER_ID)
            .apply()

        com.plugpdv.pdv.utils.CashierAuthorityStore.clearAuthority(this)
    }

    private fun showBlockedBanner(reason: String) {
        // Banner vermelho programático no topo da tela
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#C62828"))
            val padding = (12 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val textView = TextView(this).apply {
            text = "⚠️ Sessão encerrada pelo administrador.\nMotivo: $reason"
            setTextColor(Color.WHITE)
            textSize = 13f
        }
        banner.addView(textView)

        // Inserir no topo do layout raiz
        val root = binding.root
        if (root is android.view.ViewGroup) {
            root.addView(banner, 0)
        }
    }
}
