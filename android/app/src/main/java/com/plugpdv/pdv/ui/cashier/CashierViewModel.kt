package com.plugpdv.pdv.ui.cashier

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.models.CashierHistoryResponse
import com.plugpdv.pdv.models.CashierRequest
import com.plugpdv.pdv.models.CashierSession
import com.plugpdv.pdv.utils.retryIO
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class CashierResult {
    data class Success(val action: String) : CashierResult()
    data class Error(val message: String) : CashierResult()
}

@HiltViewModel
class CashierViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: PosApiService
) : ViewModel() {

    private val _history = MutableLiveData<List<CashierSession>>(emptyList())
    val history: LiveData<List<CashierSession>> = _history

    private val _currentSessionId = MutableLiveData<String?>(null)
    val currentSessionId: LiveData<String?> = _currentSessionId

    private val _cashierState = MutableLiveData<com.plugpdv.pdv.utils.CashierAuthorityState>(
        com.plugpdv.pdv.utils.CashierAuthorityState.UNKNOWN()
    )
    val cashierState: LiveData<com.plugpdv.pdv.utils.CashierAuthorityState> = _cashierState

    private val _isClosed = MutableLiveData(false)
    val isClosed: LiveData<Boolean> = _isClosed

    private val _isOffline = MutableLiveData(false)
    val isOffline: LiveData<Boolean> = _isOffline

    private val _sessionExpired = MutableLiveData(false)
    val sessionExpired: LiveData<Boolean> = _sessionExpired

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _operationResult = MutableLiveData<CashierResult?>(null)
    val operationResult: LiveData<CashierResult?> = _operationResult

    init {
        loadInitialAuthority()
    }

    fun loadInitialAuthority() {
        val activeTenantId = com.plugpdv.pdv.utils.TenantBindingStore.getActiveTenantId(context)
        val prefs = context.getSharedPreferences(com.plugpdv.pdv.utils.Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val activeUserId = prefs.getString(com.plugpdv.pdv.utils.Constants.USER_ID, null)
        val initial = com.plugpdv.pdv.utils.CashierAuthorityStore.getAuthority(context, activeTenantId, activeUserId)
        _cashierState.value = initial
        _isClosed.value = (initial is com.plugpdv.pdv.utils.CashierAuthorityState.CLOSED)
        _currentSessionId.value = (initial as? com.plugpdv.pdv.utils.CashierAuthorityState.OPEN)?.sessionId
    }

    fun fetchHistory(token: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                fetchHistoryInternal(token)
                _isOffline.value = false
            } catch (e: java.io.IOException) {
                Log.e("CashierViewModel", "IO error fetching cashier history: ${e.message}", e)
                _isOffline.value = true
                // INVARIANTE: Falha de rede NUNCA converte estado conhecido OPEN para CLOSED
            } catch (e: retrofit2.HttpException) {
                val code = e.code()
                if (code in 500..599) {
                    Log.e("CashierViewModel", "Server 5xx error ($code) fetching history: ${e.message}", e)
                    _isOffline.value = true
                    // Server degraded: preserve last-known authority
                } else if (code == 401) {
                    Log.w("CashierViewModel", "401 Unauthorized fetching history")
                    _isOffline.value = false
                    _sessionExpired.value = true
                } else if (code == 403) {
                    Log.w("CashierViewModel", "403 Forbidden fetching history")
                    _isOffline.value = false
                } else {
                    Log.e("CashierViewModel", "HTTP $code fetching history: ${e.message}", e)
                    _isOffline.value = false
                }
            } catch (e: Exception) {
                Log.e("CashierViewModel", "Failed to fetch history: ${e.message}", e)
                _isOffline.value = true
            } finally {
                _isLoading.value = false
            }
        }
    }

    private suspend fun fetchHistoryInternal(token: String) {
        fetchExchangeRates(token)
        val response = retryIO { apiService.getCashierHistory("Bearer $token", null) }
        val sessions = response.operacoes ?: response.history ?: response.data ?: emptyList()
        _history.value = sessions

        var closed = true
        var sessionId: String? = null

        if (sessions.isNotEmpty()) {
            val latest = sessions[0]
            val tipo = latest.tipo?.uppercase() ?: ""
            if (!(tipo.contains("FECHAR") || tipo.contains("CLOSE") || tipo.contains("FECHAMENTO"))) {
                closed = false
            }

            for (session in sessions) {
                val sTipo = session.tipo?.uppercase() ?: ""
                if (sTipo.contains("ABERTURA") || sTipo.contains("OPEN")) {
                    sessionId = session.caixa_session_id ?: session.id
                    break
                }
                if (sTipo.contains("FECHAR") || sTipo.contains("CLOSE") || sTipo.contains("FECHAMENTO")) {
                    break
                }
            }

            if (!closed && sessionId == null) {
                sessionId = latest.caixa_session_id ?: latest.id
            }
        }

        val activeTenantId = com.plugpdv.pdv.utils.TenantBindingStore.getActiveTenantId(context)
        val prefs = context.getSharedPreferences(com.plugpdv.pdv.utils.Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val activeUserId = prefs.getString(com.plugpdv.pdv.utils.Constants.USER_ID, null)

        if (!closed && !sessionId.isNullOrEmpty() && !activeTenantId.isNullOrBlank()) {
            com.plugpdv.pdv.utils.CashierAuthorityStore.setOpen(context, activeTenantId, activeUserId, sessionId)
            _cashierState.value = com.plugpdv.pdv.utils.CashierAuthorityState.OPEN(
                sessionId = sessionId,
                tenantId = activeTenantId,
                userId = activeUserId,
                updatedAt = System.currentTimeMillis()
            )
            _isClosed.value = false
            _currentSessionId.value = sessionId
        } else if (closed && !activeTenantId.isNullOrBlank()) {
            com.plugpdv.pdv.utils.CashierAuthorityStore.setClosed(context, activeTenantId, activeUserId)
            _cashierState.value = com.plugpdv.pdv.utils.CashierAuthorityState.CLOSED(
                tenantId = activeTenantId,
                userId = activeUserId,
                updatedAt = System.currentTimeMillis()
            )
            _isClosed.value = true
            _currentSessionId.value = null
        } else {
            _isClosed.value = closed
            _currentSessionId.value = sessionId
        }
    }

    fun performOperation(token: String, action: String, amount: Double) {
        if (_isOffline.value == true) {
            _operationResult.value = CashierResult.Error("Sem conexão. Operações de caixa requerem conexão com o servidor.")
            return
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                val currency = com.plugpdv.pdv.utils.CurrencyManager.getInstance().selectedCurrency
                val request = CashierRequest(action = action, valor = amount, moeda = currency)
                request.session_id = _currentSessionId.value
                
                val response = retryIO { apiService.operateCashier("Bearer $token", request) }
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string() ?: "Unknown error"
                    throw Exception("HTTP ${response.code()}: $errorBody")
                }
                
                fetchHistoryInternal(token)
                _operationResult.value = CashierResult.Success(action)
            } catch (e: Exception) {
                Log.e("CashierViewModel", "Operation failed", e)
                _operationResult.value = CashierResult.Error("Falha na operação: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearResult() {
        _operationResult.value = null
    }

    private fun fetchExchangeRates(token: String) {
        viewModelScope.launch {
            try {
                val request = com.plugpdv.pdv.models.ExchangeRequest(action = "listar")
                val response = com.plugpdv.pdv.utils.retryIO { apiService.getExchangeRates("Bearer $token", request) }
                com.plugpdv.pdv.utils.CurrencyManager.getInstance().setRates(response)
            } catch (e: Exception) {
                Log.e("CashierViewModel", "Failed to fetch exchange rates", e)
            }
        }
    }
}
