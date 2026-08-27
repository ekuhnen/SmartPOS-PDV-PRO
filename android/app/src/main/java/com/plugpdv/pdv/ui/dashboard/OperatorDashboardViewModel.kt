package com.plugpdv.pdv.ui.dashboard

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.models.CashierSession
import com.plugpdv.pdv.models.ReportSummary
import com.plugpdv.pdv.models.SaleHistoryItem
import com.plugpdv.pdv.repository.DateFilterOption
import com.plugpdv.pdv.repository.ReportRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OperatorDashboardViewModel @Inject constructor(
    private val reportRepository: ReportRepository
) : ViewModel() {

    private val _sales = MutableLiveData<List<SaleHistoryItem>>(emptyList())
    val sales: LiveData<List<SaleHistoryItem>> = _sales

    private val _operations = MutableLiveData<List<CashierSession>>(emptyList())
    val operations: LiveData<List<CashierSession>> = _operations

    private val _reportSummary = MutableLiveData<ReportSummary>()
    val reportSummary: LiveData<ReportSummary> = _reportSummary

    private val _selectedDateOption = MutableLiveData(DateFilterOption.TODAY)
    val selectedDateOption: LiveData<DateFilterOption> = _selectedDateOption

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private var lastToken: String? = null
    private var lastSessionId: String? = null

    fun setDateFilter(option: DateFilterOption) {
        _selectedDateOption.value = option
        fetchData(lastToken, lastSessionId)
    }

    fun fetchData(token: String?, sessionId: String?) {
        this.lastToken = token
        this.lastSessionId = sessionId
        val dateOption = _selectedDateOption.value ?: DateFilterOption.TODAY

        viewModelScope.launch {
            try {
                _isLoading.value = true
                val summary = reportRepository.getReport(token, sessionId, dateOption)
                _reportSummary.value = summary
                _sales.value = summary.sales
            } catch (e: Exception) {
                Log.e("DashboardViewModel", "Failed to fetch dashboard data", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
}
