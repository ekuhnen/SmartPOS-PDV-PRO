package com.plugpdv.pdv.ui.dashboard

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.models.DashboardReport
import com.plugpdv.pdv.repository.DateFilterOption
import com.plugpdv.pdv.repository.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class DashboardUiState {
    object Loading : DashboardUiState()
    data class Success(val report: DashboardReport, val loadingMore: Boolean = false) : DashboardUiState()
    data class Error(val cause: Throwable) : DashboardUiState()
}

@HiltViewModel
class OperatorDashboardViewModel @Inject constructor(private val repository: ReportRepository) : ViewModel() {
    private val _state = MutableLiveData<DashboardUiState>()
    val state: LiveData<DashboardUiState> = _state
    private var token: String? = null
    private var option = DateFilterOption.TODAY

    fun setDateFilter(value: DateFilterOption) { option = value; fetchData(token, null) }

    fun fetchData(accessToken: String?, ignoredSessionId: String?) {
        token = accessToken
        val validToken = accessToken?.takeIf { it.isNotBlank() }
        if (validToken == null) { _state.value = DashboardUiState.Error(IllegalStateException("AUTH_SESSION_MISSING")); return }
        viewModelScope.launch {
            _state.value = DashboardUiState.Loading
            _state.value = repository.getReportResult(validToken, option).fold(
                onSuccess = { DashboardUiState.Success(it) },
                onFailure = { DashboardUiState.Error(it) }
            )
        }
    }

    fun loadNextPage() {
        val current = (_state.value as? DashboardUiState.Success)?.report ?: return
        val validToken = token ?: return
        if (!current.pagination.hasMore) return
        _state.value = DashboardUiState.Success(current, loadingMore = true)
        viewModelScope.launch {
            _state.value = repository.getReportResult(validToken, option, current.pagination.limit, current.pagination.offset + current.history.size).fold(
                onSuccess = { DashboardUiState.Success(it.copy(history = current.history + it.history)) },
                onFailure = { DashboardUiState.Success(current) }
            )
        }
    }
}
