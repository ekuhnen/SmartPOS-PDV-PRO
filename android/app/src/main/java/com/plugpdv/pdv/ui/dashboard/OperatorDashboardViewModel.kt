package com.plugpdv.pdv.ui.dashboard

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.models.CashierSession
import com.plugpdv.pdv.models.SaleHistoryItem
import com.plugpdv.pdv.utils.retryIO
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class OperatorDashboardViewModel @Inject constructor(
    private val apiService: PosApiService
) : ViewModel() {

    private val _sales = MutableLiveData<List<SaleHistoryItem>>(emptyList())
    val sales: LiveData<List<SaleHistoryItem>> = _sales

    private val _operations = MutableLiveData<List<CashierSession>>(emptyList())
    val operations: LiveData<List<CashierSession>> = _operations

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    fun fetchData(token: String, sessionId: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val salesDeferred = async { retryIO { apiService.getSales("Bearer $token", sessionId) } }
                val historyDeferred = async { retryIO { apiService.getCashierHistory("Bearer $token", null) } }

                val salesResponse = try { salesDeferred.await() } catch (e: Exception) { null }
                val historyResponse = try { historyDeferred.await() } catch (e: Exception) { null }

                val salesList = salesResponse?.let { it.sales ?: it.data ?: it.items } ?: emptyList()
                _sales.value = salesList

                val allOps = historyResponse?.let { it.operacoes ?: it.history ?: it.data } ?: emptyList()
                val sessionOps = allOps.filter { it.caixa_session_id == sessionId || it.id == sessionId }
                _operations.value = sessionOps

            } catch (e: Exception) {
                Log.e("DashboardViewModel", "Failed to fetch dashboard data", e)
            } finally {
                _isLoading.value = false
            }
        }
    }
}
