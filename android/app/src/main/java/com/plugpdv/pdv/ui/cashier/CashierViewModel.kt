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
import com.plugpdv.pdv.models.DashboardMoney
import com.plugpdv.pdv.repository.DateFilterOption
import com.plugpdv.pdv.repository.ReportRepository
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
    private val apiService: PosApiService,
    private val reportRepository: ReportRepository = ReportRepository(apiService)
) : ViewModel() {

    data class CashSummary(
        val opening: List<DashboardMoney> = emptyList(),
        val collectedByMethod: Map<String, List<DashboardMoney>> = emptyMap(),
        val withdrawals: List<DashboardMoney> = emptyList()
    ) {
        fun cashToRender(): List<DashboardMoney> {
            val cash = collectedByMethod.filterKeys { it.equals("DINHEIRO", true) || it.equals("CASH", true) }
                .values.flatten().groupBy { it.currency }
                .map { (currency, values) -> DashboardMoney(values.sumOf { it.amountMinor }, currency) }
                .associateBy { it.currency }
            val sangria = withdrawals.groupBy { it.currency }
                .mapValues { (_, values) -> values.sumOf { it.amountMinor } }
            return cash.mapNotNull { (currency, value) ->
                DashboardMoney((value.amountMinor - (sangria[currency] ?: 0L)).coerceAtLeast(0L), currency)
            }
        }
    }

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

    private val _cashSummary = MutableLiveData(CashSummary())
    val cashSummary: LiveData<CashSummary> = _cashSummary

    init {
        loadInitialAuthority()
    }

    fun loadInitialAuthority() {
        val activeTenantId = com.plugpdv.pdv.utils.TenantBindingStore.getActiveTenantId(context)
        val prefs = context.getSharedPreferences(com.plugpdv.pdv.utils.Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val activeUserId = prefs.getString(com.plugpdv.pdv.utils.Constants.USER_ID, null)

        val initial = if (activeTenantId.isNullOrBlank() || activeUserId.isNullOrBlank()) {
            com.plugpdv.pdv.utils.CashierAuthorityState.UNKNOWN(
                reason = if (activeTenantId.isNullOrBlank()) "MISSING_ACTIVE_TENANT" else "MISSING_ACTIVE_USER"
            )
        } else {
            com.plugpdv.pdv.utils.CashierAuthorityStore.getAuthority(context, activeTenantId, activeUserId)
        }
        _cashierState.value = initial
        _isClosed.value = (initial is com.plugpdv.pdv.utils.CashierAuthorityState.CLOSED)
        _currentSessionId.value = (initial as? com.plugpdv.pdv.utils.CashierAuthorityState.OPEN)?.sessionId
    }

    fun fetchHistory(token: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                fetchHistoryInternal(token)
                fetchCashSummary(token)
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

    private suspend fun fetchCashSummary(token: String) {
        val report = reportRepository.getReportResult(token, DateFilterOption.TODAY).getOrNull() ?: return
        val opening = report.cashOperations.filter { it.type.contains("ABERT", true) || it.type.contains("OPEN", true) }
            .map { it.money }
        val withdrawals = report.cashOperations.filter {
            it.type.contains("RETIR", true) || it.type.contains("SANGR", true) || it.type.contains("WITHDRAW", true)
        }.map { it.money }
        val byMethod = report.payments
            .filterNot { it.method.equals("REGISTRADO", true) || it.method.equals("ESTORNADO", true) }
            .groupBy { it.method }
            .mapValues { (_, values) -> values.map { it.money } }
        _cashSummary.postValue(CashSummary(opening, byMethod, withdrawals))
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

        if (activeTenantId.isNullOrBlank() || activeUserId.isNullOrBlank()) {
            _cashierState.value = com.plugpdv.pdv.utils.CashierAuthorityState.UNKNOWN(
                reason = if (activeTenantId.isNullOrBlank()) "MISSING_ACTIVE_TENANT" else "MISSING_ACTIVE_USER"
            )
            _isClosed.value = false
            _currentSessionId.value = null
            return
        }

        if (!closed && !sessionId.isNullOrEmpty()) {
            com.plugpdv.pdv.utils.CashierAuthorityStore.setOpen(context, activeTenantId, activeUserId, sessionId)
            _cashierState.value = com.plugpdv.pdv.utils.CashierAuthorityState.OPEN(
                sessionId = sessionId,
                tenantId = activeTenantId,
                userId = activeUserId,
                updatedAt = System.currentTimeMillis()
            )
            _isClosed.value = false
            _currentSessionId.value = sessionId
        } else if (closed) {
            com.plugpdv.pdv.utils.CashierAuthorityStore.setClosed(context, activeTenantId, activeUserId)
            _cashierState.value = com.plugpdv.pdv.utils.CashierAuthorityState.CLOSED(
                tenantId = activeTenantId,
                userId = activeUserId,
                updatedAt = System.currentTimeMillis()
            )
            _isClosed.value = true
            _currentSessionId.value = null
        } else {
            _cashierState.value = com.plugpdv.pdv.utils.CashierAuthorityState.UNKNOWN("INCONSISTENT_REMOTE_RESPONSE")
            _isClosed.value = false
            _currentSessionId.value = null
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
                fetchCashSummary(token)
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
