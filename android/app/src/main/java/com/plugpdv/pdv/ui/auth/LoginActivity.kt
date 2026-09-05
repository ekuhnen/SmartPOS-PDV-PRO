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
import android.content.ClipboardManager
import android.content.ClipData
import android.os.SystemClock
import android.util.Log
import com.plugpdv.pdv.service.DeviceGuardService
import com.plugpdv.pdv.utils.DeviceIdProvider
import com.plugpdv.pdv.utils.RememberedCredentialsStore
import javax.inject.Inject

@AndroidEntryPoint
class LoginActivity : BaseActivity() {
    private lateinit var binding: ActivityLoginBinding
    private lateinit var rememberedCredentials: RememberedCredentialsStore
    private val viewModel: AuthViewModel by viewModels()
    private var loginTapElapsedRealtime: Long = 0L

    @Inject
    lateinit var deviceGuardService: DeviceGuardService

    @Inject
    lateinit var saleSyncScheduler: com.plugpdv.pdv.outbox.SaleSyncScheduler

    @Inject
    lateinit var comandaWorkScheduler: com.plugpdv.pdv.worker.ComandaWorkScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        rememberedCredentials = RememberedCredentialsStore(this)
        // Remove the legacy plaintext password key from pre-03B4 installations.
        getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(Constants.PASSWORD).apply()
        restoreRememberedCredentials()

        // Exibir banner de bloqueio se vier de um force_logout
        intent.getStringExtra(KillSwitchManager.EXTRA_BLOCKED_REASON)?.let { reason ->
            showBlockedBanner(reason)
            KillSwitchManager.reset()
        }

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString()
            val password = binding.etPassword.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            loginTapElapsedRealtime = SystemClock.elapsedRealtime()
            viewModel.login(email, password, loginTapElapsedRealtime)
        }

        binding.cbRememberCredentials.setOnCheckedChangeListener { _, checked ->
            if (!checked) rememberedCredentials.clear()
        }

        binding.btnLangPt.setOnClickListener { changeLanguage("pt") }
        binding.btnLangEs.setOnClickListener { changeLanguage("es") }

        observeViewModel()

        // Remembered credentials prefill the form; authentication remains explicit.
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
                        .remove(Constants.PASSWORD)
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

                    if (binding.cbRememberCredentials.isChecked) {
                        rememberedCredentials.save(email, password)
                    } else {
                        rememberedCredentials.clear()
                    }
                        
                    // Iniciar monitoramento Realtime (Kill-Switch)
                    deviceGuardService.start(this, result.userId, DeviceIdProvider.get(this))

                    // Reagendar WorkManager imediatamente para processar outbox suspensa por falta de token
                    saleSyncScheduler.scheduleSync(this)
                    comandaWorkScheduler.scheduleCommandSync()
                        
                    if (result.isOpen) {
                        val intent = Intent(this, DirectSaleActivity::class.java).apply {
                            putExtra("ACCESS_TOKEN", result.token)
                            putExtra("LOGIN_TAP_ELAPSED_REALTIME", loginTapElapsedRealtime)
                        }
                        Log.d("PERF_LOGIN", "navigation_ms=0 blocking=BLOCKING")
                        startActivity(intent)
                        finish()
                    } else {
                        Toast.makeText(this, R.string.cashier_closed_msg, Toast.LENGTH_LONG).show()
                        val intent = Intent(this, CashierActivity::class.java).apply {
                            putExtra("ACCESS_TOKEN", result.token)
                            putExtra("LOGIN_TAP_ELAPSED_REALTIME", loginTapElapsedRealtime)
                        }
                        Log.d("PERF_LOGIN", "navigation_ms=0 blocking=BLOCKING")
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

    private fun restoreRememberedCredentials() {
        val credentials = rememberedCredentials.load() ?: return
        binding.etEmail.setText(credentials.login)
        binding.etPassword.setText(credentials.password)
        binding.cbRememberCredentials.isChecked = true
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
