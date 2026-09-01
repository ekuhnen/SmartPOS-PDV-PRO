package com.plugpdv.pdv.ui.reconciliation

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.database.ComandaMutationEntity
import com.plugpdv.pdv.repository.ComandaMutationRepository
import com.plugpdv.pdv.repository.ReconciliationResult
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.DeviceIdProvider
import com.plugpdv.pdv.utils.TenantBindingStore
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class ReconciliationUiState {
    data class Success(val message: String, val action: String) : ReconciliationUiState()
    data class Rejected(val reason: String) : ReconciliationUiState()
    data class AlreadyResolved(val message: String) : ReconciliationUiState()
}

@HiltViewModel
class ReconciliationViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ComandaMutationRepository
) : ViewModel() {

    private val _reconciliations = MutableLiveData<List<ComandaMutationEntity>>(emptyList())
    val reconciliations: LiveData<List<ComandaMutationEntity>> = _reconciliations

    private val _count = MutableLiveData<Int>(0)
    val count: LiveData<Int> = _count

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _uiState = MutableLiveData<ReconciliationUiState?>(null)
    val uiState: LiveData<ReconciliationUiState?> = _uiState

    /**
     * Extracts the active authority triplet from context/preferences.
     */
    fun getAuthorityTriplet(): Triple<String, String, String>? {
        val tenantId = TenantBindingStore.getActiveTenantId(context)
        val deviceId = DeviceIdProvider.get(context)
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val actorUserId = prefs.getString(Constants.USER_ID, null) ?: prefs.getString(Constants.OPERATOR_ID, null)

        if (tenantId.isNullOrBlank() || actorUserId.isNullOrBlank() || deviceId.isBlank()) {
            return null
        }
        return Triple(tenantId, actorUserId, deviceId)
    }

    /**
     * Loads mutations requiring reconciliation for the current authority triplet.
     */
    fun loadReconciliations() {
        val triplet = getAuthorityTriplet()
        if (triplet == null) {
            _reconciliations.value = emptyList()
            _count.value = 0
            return
        }
        val (tenantId, actorUserId, deviceId) = triplet
        viewModelScope.launch {
            _isLoading.value = true
            val list = repository.getReconciliationRequiredForAuthority(tenantId, actorUserId, deviceId)
            _reconciliations.value = list
            _count.value = list.size
            _isLoading.value = false
        }
    }

    /**
     * R-A: Confirm Remote Success.
     */
    fun resolveAsCompleted(mutationId: String, notes: String? = null) {
        val triplet = getAuthorityTriplet()
        if (triplet == null) {
            _uiState.value = ReconciliationUiState.Rejected("Credenciais de autoridade ausentes")
            return
        }
        val (tenantId, actorUserId, deviceId) = triplet

        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.resolveReconciliationAsCompleted(
                mutationId = mutationId,
                actorUserId = actorUserId,
                deviceId = deviceId,
                tenantId = tenantId,
                notes = notes
            )
            handleResult(result, "COMPLETED")
        }
    }

    /**
     * R-B: Retry Original Mutation.
     */
    fun resolveAsRetry(mutationId: String, notes: String? = null) {
        val triplet = getAuthorityTriplet()
        if (triplet == null) {
            _uiState.value = ReconciliationUiState.Rejected("Credenciais de autoridade ausentes")
            return
        }
        val (tenantId, actorUserId, deviceId) = triplet

        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.resolveReconciliationAsRetry(
                mutationId = mutationId,
                actorUserId = actorUserId,
                deviceId = deviceId,
                tenantId = tenantId,
                notes = notes
            )
            handleResult(result, "RETRY")
        }
    }

    /**
     * R-C: Cancel Local Intent.
     */
    fun resolveAsCancelled(mutationId: String, notes: String? = null) {
        val triplet = getAuthorityTriplet()
        if (triplet == null) {
            _uiState.value = ReconciliationUiState.Rejected("Credenciais de autoridade ausentes")
            return
        }
        val (tenantId, actorUserId, deviceId) = triplet

        viewModelScope.launch {
            _isLoading.value = true
            val result = repository.resolveReconciliationAsCancelled(
                mutationId = mutationId,
                actorUserId = actorUserId,
                deviceId = deviceId,
                tenantId = tenantId,
                notes = notes
            )
            handleResult(result, "CANCELLED")
        }
    }

    private fun handleResult(result: ReconciliationResult, action: String) {
        _isLoading.value = false
        when (result) {
            is ReconciliationResult.Success -> {
                _uiState.value = ReconciliationUiState.Success(
                    message = when (action) {
                        "COMPLETED" -> "Operação confirmada como concluída com sucesso."
                        "RETRY" -> "Reenvio agendado com sucesso."
                        "CANCELLED" -> "Operação cancelada com sucesso."
                        else -> "Operação resolvida."
                    },
                    action = action
                )
                loadReconciliations()
            }
            is ReconciliationResult.Rejected -> {
                _uiState.value = ReconciliationUiState.Rejected(
                    "Esta operação não pode ser resolvida por este usuário ou neste terminal."
                )
            }
            is ReconciliationResult.AlreadyResolved -> {
                _uiState.value = ReconciliationUiState.AlreadyResolved(
                    "Esta operação já foi resolvida."
                )
                loadReconciliations()
            }
        }
    }

    fun clearUiState() {
        _uiState.value = null
    }
}
