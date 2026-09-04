package com.plugpdv.pdv.ui.cashier

import android.content.Context
import androidx.lifecycle.*
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.utils.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import retrofit2.Response
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject

enum class CashLoading { REFRESH, OPEN, MOVEMENT, PREVIEW, CLOSE }
sealed class CashierResult {
    data class Opened(val response: CashActionResponse) : CashierResult()
    data class MovementCreated(val action: String, val response: CashActionResponse) : CashierResult()
    data class PreviewReady(val snapshot: CashSessionSnapshot) : CashierResult()
    data class Closed(val snapshot: CashSessionSnapshot) : CashierResult()
    data class Error(val messageKey: String?, val code: String?, val unexpected: Boolean) : CashierResult() {
        val message: String get() = if (messageKey == "cash.error.network_required") "Sem conexão" else (code ?: messageKey.orEmpty())
    }
}

@HiltViewModel
class CashierViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PosApiService
) : ViewModel() {
    private val gson = Gson()
    private val _session = MutableLiveData<CashSessionSnapshot?>(null)
    val session: LiveData<CashSessionSnapshot?> = _session
    private val _loaded = MutableLiveData(false)
    val loaded: LiveData<Boolean> = _loaded
    private val _loading = MutableLiveData<CashLoading?>(null)
    val loading: LiveData<CashLoading?> = _loading
    private val _result = MutableLiveData<CashierResult?>(null)
    val result: LiveData<CashierResult?> = _result
    private val _isOffline = MutableLiveData(false)
    val isOffline: LiveData<Boolean> = _isOffline

    // Compatibility surface for non-UI recovery tests. The cash screen uses only [session].
    private val _cashierState = MutableLiveData<CashierAuthorityState>(initialAuthority())
    val cashierState: LiveData<CashierAuthorityState> = _cashierState
    private val _isClosed = MutableLiveData(_cashierState.value is CashierAuthorityState.CLOSED)
    val isClosed: LiveData<Boolean> = _isClosed
    private val _currentSessionId = MutableLiveData((_cashierState.value as? CashierAuthorityState.OPEN)?.sessionId)
    val currentSessionId: LiveData<String?> = _currentSessionId
    private val _sessionExpired = MutableLiveData(false)
    val sessionExpired: LiveData<Boolean> = _sessionExpired
    val isLoading: LiveData<Boolean> = MediatorLiveData<Boolean>().apply {
        value = false
        addSource(_loading) { value = it != null }
    }
    val operationResult: LiveData<CashierResult?> = result

    fun refresh(token: String) = launch(CashLoading.REFRESH) {
        val current = api.getCurrentCashSession("Bearer $token").session
        _session.value = current
        _loaded.value = true
        _isOffline.value = false
        persistAuthority(current)
    }

    @Deprecated("The UI must use refresh(), which reads current_session=1")
    fun fetchHistory(token: String) {
        if (_loading.value != null) return
        viewModelScope.launch {
            _loading.value = CashLoading.REFRESH
            try {
                val response = api.getCashierHistory("Bearer $token", null)
                val rows = response.operacoes ?: response.history ?: response.data.orEmpty()
                val tenant = TenantBindingStore.getActiveTenantId(context)
                val user = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).getString(Constants.USER_ID, null)
                if (tenant.isNullOrBlank() || user.isNullOrBlank()) {
                    _cashierState.value = CashierAuthorityState.UNKNOWN(if (tenant.isNullOrBlank()) "MISSING_ACTIVE_TENANT" else "MISSING_ACTIVE_USER")
                } else {
                    val latest = rows.firstOrNull()
                    val closed = latest == null || latest.tipo.orEmpty().contains("FECH", true) || latest.tipo.orEmpty().contains("CLOSE", true)
                    if (closed) _cashierState.value = CashierAuthorityState.CLOSED(tenant, user, System.currentTimeMillis())
                    else {
                        val id = rows.firstOrNull { it.tipo.orEmpty().contains("ABERT", true) || it.tipo.orEmpty().contains("OPEN", true) }?.let { it.caixa_session_id ?: it.id }
                        if (id != null) _cashierState.value = CashierAuthorityState.OPEN(id, tenant, user, System.currentTimeMillis())
                    }
                }
                _isClosed.value = _cashierState.value is CashierAuthorityState.CLOSED
                _currentSessionId.value = (_cashierState.value as? CashierAuthorityState.OPEN)?.sessionId
                _isOffline.value = false
            } catch (e: retrofit2.HttpException) {
                if (e.code() == 401) _sessionExpired.value = true
                if (e.code() >= 500) _isOffline.value = true
            } catch (_: java.io.IOException) { _isOffline.value = true }
            finally { _loading.value = null }
        }
    }

    @Deprecated("Cash UI uses typed decimal operations")
    fun performOperation(token: String, action: String, amount: Double) {
        if (_isOffline.value == true) { _result.value = CashierResult.Error("cash.error.network_required", null, true); return }
        val currency = _session.value?.openingCurrency ?: CurrencyManager.getInstance().selectedCurrency
        if (action == "abrir") open(token, currency, BigDecimal.valueOf(amount), null)
        else movement(token, action, currency, BigDecimal.valueOf(amount), "legacy", null)
    }

    fun open(token: String, currency: String, amount: BigDecimal, reason: String?) =
        execute(token, CashLoading.OPEN, CashierRequest("abrir", amount, currency, observacao = reason)) {
            _result.value = CashierResult.Opened(it)
        }

    fun movement(token: String, action: String, currency: String, amount: BigDecimal, reason: String, reference: String?) {
        val id = _session.value?.sessionId ?: return
        execute(token, CashLoading.MOVEMENT, CashierRequest(action, amount, currency, id, reason, reference)) {
            _result.value = CashierResult.MovementCreated(action, it)
        }
    }

    fun previewClose(token: String) {
        val id = _session.value?.sessionId ?: return
        execute(token, CashLoading.PREVIEW, CashierRequest("preview_fechamento", sessionId = id), false) {
            _result.value = CashierResult.PreviewReady(it.toSnapshot())
        }
    }

    fun close(token: String, counted: List<CountedCashRequest>?) {
        val id = _session.value?.sessionId ?: return
        execute(token, CashLoading.CLOSE, CashierRequest("fechar", sessionId = id, countedByCurrency = counted)) {
            val snapshot = it.toSnapshot()
            _session.value = null
            persistAuthority(null)
            _result.value = CashierResult.Closed(snapshot)
        }
    }

    private fun execute(token: String, kind: CashLoading, request: CashierRequest,
                        idempotent: Boolean = true, success: (CashActionResponse) -> Unit) {
        if (_loading.value != null) return
        _loading.value = kind
        val signature = gson.toJson(request)
        // Generated and synchronously persisted in the same call initiated by the operator tap.
        val key = if (idempotent) pendingKey(signature) else null
        viewModelScope.launch {
            try {
                val response = api.operateCashier("Bearer $token", key, request)
                if (!response.isSuccessful) {
                    if (response.code() in 400..499) clearPending()
                    publishError(response)
                    return@launch
                }
                val body = response.body() ?: throw IllegalStateException("EMPTY_CASH_RESPONSE")
                clearPending()
                if (kind != CashLoading.CLOSE && kind != CashLoading.PREVIEW) {
                    _session.value = api.getCurrentCashSession("Bearer $token").session
                    persistAuthority(_session.value)
                }
                success(body)
            } catch (_: java.io.IOException) {
                _isOffline.value = true
                _result.value = CashierResult.Error("cash.error.network_required", null, true)
            } catch (_: Exception) {
                _result.value = CashierResult.Error("cash.error.unexpected", null, true)
            } finally { _loading.value = null }
        }
    }

    private fun launch(kind: CashLoading, block: suspend () -> Unit) {
        if (_loading.value != null) return
        _loading.value = kind
        viewModelScope.launch {
            try { block() }
            catch (_: java.io.IOException) { _isOffline.value = true; _result.value = CashierResult.Error("cash.error.network_required", null, true) }
            catch (_: Exception) { _result.value = CashierResult.Error("cash.error.unexpected", null, true) }
            finally { _loading.value = null }
        }
    }

    private fun publishError(response: Response<CashActionResponse>) {
        val error = runCatching { gson.fromJson(response.errorBody()?.string(), CashApiError::class.java) }.getOrNull()
        _result.value = CashierResult.Error(error?.messageKey, error?.code, response.code() >= 500)
    }

    private fun pendingKey(signature: String): String {
        val p = context.getSharedPreferences("cash_operation_pending", Context.MODE_PRIVATE)
        if (p.getString("signature", null) == signature) return p.getString("key", null) ?: UUID.randomUUID().toString()
        val key = UUID.randomUUID().toString()
        p.edit().putString("signature", signature).putString("key", key).commit()
        return key
    }
    private fun clearPending() { context.getSharedPreferences("cash_operation_pending", Context.MODE_PRIVATE).edit().clear().commit() }

    private fun persistAuthority(snapshot: CashSessionSnapshot?) {
        val tenant = TenantBindingStore.getActiveTenantId(context) ?: return
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val user = prefs.getString(Constants.USER_ID, null) ?: return
        if (snapshot == null) CashierAuthorityStore.setClosed(context, tenant, user)
        else CashierAuthorityStore.setOpen(context, tenant, user, snapshot.sessionId)
    }
    private fun initialAuthority(): CashierAuthorityState {
        val tenant = TenantBindingStore.getActiveTenantId(context)
        val user = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE).getString(Constants.USER_ID, null)
        return if (tenant.isNullOrBlank() || user.isNullOrBlank()) CashierAuthorityState.UNKNOWN(if (tenant.isNullOrBlank()) "MISSING_ACTIVE_TENANT" else "MISSING_ACTIVE_USER")
        else CashierAuthorityStore.getAuthority(context, tenant, user)
    }
    fun clearResult() { _result.value = null }
}
