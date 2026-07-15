package com.plugpdv.pdv.ui.auth

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.models.AuthResponse
import com.plugpdv.pdv.models.ExchangeRequest
import com.plugpdv.pdv.models.LoginRequest
import com.plugpdv.pdv.repository.TaxRepository
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.DeviceIdProvider
import com.plugpdv.pdv.utils.KillSwitchManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.gotrue.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed class LoginResult {
    data class Success(
        val userId: String,
        val token: String, 
        val isOpen: Boolean,
        val sessionId: String?,
        val hasMesa: Boolean,
        val hasVendaDireta: Boolean,
        val hasComanda: Boolean
    ) : LoginResult()
    data class Error(val message: String) : LoginResult()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: PosApiService,
    private val taxRepository: TaxRepository,
    private val supabase: SupabaseClient,
    private val database: AppDatabase
) : ViewModel() {

    private val _loginResult = MutableLiveData<LoginResult?>(null)
    val loginResult: LiveData<LoginResult?> = _loginResult

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    fun login(email: String, password: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                Log.d("AuthViewModel", "Iniciando chamada de login para: $email")
                
                val response = apiService.login(LoginRequest(email, password))
                val token = response.access_token ?: throw Exception("Token null")
                
                Log.d("AuthViewModel", "Login OK! Limpando dados do banco local...")
                withContext(Dispatchers.IO) {
                    database.clearAllTables()
                }
                
                Log.d("AuthViewModel", "Iniciando registro no Supabase...")
                
                // Registrar dispositivo no backend (best-effort)
                viewModelScope.launch { registerDevice(token) }

                Log.d("AuthViewModel", "Buscando histórico de caixa...")
                // Check cashier status
                val cashierResponse = apiService.getCashierHistory("Bearer $token", null)
                val sessions = cashierResponse.operacoes ?: cashierResponse.history ?: cashierResponse.data
                
                var isOpen = false
                var sessionId: String? = null
                if (!sessions.isNullOrEmpty()) {
                    val latest = sessions[0]
                    val tipo = latest.tipo?.uppercase() ?: ""
                    if (!(tipo.contains("FECHAR") || tipo.contains("CLOSE") || tipo.contains("FECHAMENTO"))) {
                        isOpen = true
                        for (session in sessions) {
                            val sTipo = session.tipo?.uppercase() ?: ""
                            if (sTipo.contains("ABERTURA") || sTipo.contains("OPEN")) {
                                sessionId = session.id
                                break
                            }
                            if (sTipo.contains("FECHAR") || sTipo.contains("CLOSE") || sTipo.contains("FECHAMENTO")) {
                                break
                            }
                        }
                    }
                }
                Log.d("AuthViewModel", "Status do caixa: ${if(isOpen) "ABERTO" else "FECHADO"}")

                Log.d("AuthViewModel", "Sincronizando taxas e moedas...")
                // Sync taxes and exchange rates unconditionally
                taxRepository.syncTaxes(token)
                fetchExchangeRates(token)

                val hasMesa = response.mesa ?: false
                val hasVendaDireta = response.venda_direta ?: false
                val hasComanda = response.comanda ?: false
                val userId = response.user?.id ?: ""

                Log.d("AuthViewModel", "Login finalizado com sucesso para o usuário: $userId")
                _loginResult.value = LoginResult.Success(userId, token, isOpen, sessionId, hasMesa, hasVendaDireta, hasComanda)
            } catch (e: Exception) {
                Log.e("AuthViewModel", "ERRO NO LOGIN: ${e.message}")
                e.printStackTrace()
                _loginResult.value = LoginResult.Error(e.message ?: "Credenciais inválidas ou erro de rede")
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun fetchExchangeRates(token: String) {
        try {
            val response = apiService.getExchangeRates("Bearer $token", ExchangeRequest(action = "listar"))
            CurrencyManager.getInstance().setRates(response)
        } catch (e: Exception) {
            Log.e("AuthViewModel", "Failed to fetch exchange rates", e)
        }
    }

    private suspend fun registerDevice(token: String) {
        try {
            val fcmToken = runCatching {
                FirebaseMessaging.getInstance().token.await()
            }.getOrNull()

            val appVersion = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName
            } catch (e: Exception) {
                "1.0"
            }

            val deviceLabel = Build.MODEL
            val userAgent = "SmartPos/${appVersion} (Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"

            // Chamada para a Edge Function conforme "Caminho 1"
            Log.d("AuthViewModel", "Iniciando registro do device via Edge Function... Token: ${token.take(10)}...")
            
            // Usamos o httpClient interno para ter controle total sobre os headers e evitar duplicidade
            val response = supabase.httpClient.post("https://ypvcxgkzolzxggfrmzlz.supabase.co/functions/v1/register-device") {
                header("Authorization", "Bearer $token")
                header("apikey", "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpc3MiOiJzdXBhYmFzZSIsInJlZiI6InlwdmN4Z2t6b2x6eGdnZnJtemx6Iiwicm9sZSI6ImFub24iLCJpYXQiOjE3NzIxMjI4NDEsImV4cCI6MjA4NzY5ODg0MX0.NsUCjtnLg4rsHNhAXItIKxvJe_nl1mX7Ssa2XxF9VhU")
                contentType(ContentType.Application.Json)
                setBody(buildMap {
                    put("fcm_token", fcmToken)
                    put("platform", "android")
                    put("device_label", deviceLabel)
                    put("app_version", appVersion)
                    put("user_agent", userAgent)
                })
            }
            
            Log.i("AuthViewModel", "Registro concluído! Status: ${response.status}")
        } catch (e: Exception) {
            Log.e("AuthViewModel", "ERRO no registro via Edge Function: ${e.message}")
            e.printStackTrace()
        }
    }
}
