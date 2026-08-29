package com.plugpdv.pdv.ui.sale

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.CatalogDao
import com.plugpdv.pdv.models.CommandActionRequest
import com.plugpdv.pdv.models.Sector
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.repository.TableReadRepository
import com.plugpdv.pdv.utils.TableManager
import com.plugpdv.pdv.utils.TransferQueueManager
import com.plugpdv.pdv.utils.retryIO
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import retrofit2.HttpException
import javax.inject.Inject

@HiltViewModel
class MesaViewModel @Inject constructor(
    private val apiService: PosApiService,
    private val catalogDao: CatalogDao,
    private val tableReadRepository: TableReadRepository,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val queueManager = TransferQueueManager(context)

    private val _tables = MutableLiveData<List<Table>>(emptyList())
    
    private val _sectors = MutableLiveData<List<Sector>>(listOf(Sector(id = "", nome = "Todos")))
    val sectors: LiveData<List<Sector>> = _sectors

    private val _selectedSectorId = MutableLiveData<String?>(null)
    val selectedSectorId: LiveData<String?> = _selectedSectorId

    private val _filteredTables = MediatorLiveData<List<Table>>().apply {
        addSource(_tables) { updateFilteredTables() }
        addSource(_selectedSectorId) { updateFilteredTables() }
    }
    val tables: LiveData<List<Table>> = _filteredTables

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _isRefreshing = MutableLiveData(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    private val _refreshWarning = MutableLiveData<String?>(null)
    val refreshWarning: LiveData<String?> = _refreshWarning

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _openSuccess = MutableLiveData(false)
    val openSuccess: LiveData<Boolean> = _openSuccess

    private val _openedComandaId = MutableLiveData<String?>(null)
    val openedComandaId: LiveData<String?> = _openedComandaId

    private val _sessionExpired = MutableLiveData<Boolean>(false)
    val sessionExpired: LiveData<Boolean> = _sessionExpired

    private var isRefreshingNetwork = false

    init {
        observeRoomTables()
    }

    private fun observeRoomTables() {
        viewModelScope.launch {
            tableReadRepository.observeTables().collectLatest { roomTables ->
                _tables.value = roomTables
                TableManager.setTables(roomTables) // Mirror for legacy compatibility
                deriveSectors(roomTables)
            }
        }
    }

    private fun deriveSectors(tableList: List<Table>) {
        val distinctSectors = tableList
            .filter { it.sectorId.isNotEmpty() }
            .distinctBy { it.sectorId }
            .map { Sector(id = it.sectorId, nome = it.sectorName.ifEmpty { "Setor ${it.sectorId}" }) }

        val allSectors = mutableListOf(Sector(id = "", nome = "Todos"))
        allSectors.addAll(distinctSectors)
        _sectors.value = allSectors
    }

    private fun updateFilteredTables() {
        val allTables = _tables.value ?: emptyList()
        val sectorId = _selectedSectorId.value
        _filteredTables.value = if (sectorId.isNullOrEmpty()) {
            allTables
        } else {
            allTables.filter { it.sectorId == sectorId }
        }
    }

    fun setSelectedSector(sectorId: String?) {
        _selectedSectorId.value = sectorId
    }

    fun consumeSessionExpired() {
        _sessionExpired.value = false
    }

    fun fetchTables(token: String) {
        if (isRefreshingNetwork) return

        viewModelScope.launch {
            isRefreshingNetwork = true
            val hasCachedData = !_tables.value.isNullOrEmpty() || tableReadRepository.getAllTables().isNotEmpty()
            if (!hasCachedData) {
                _isLoading.value = true
            }
            _isRefreshing.value = true
            _error.value = null

            try {
                // Process any pending transfers first
                queueManager.processQueue(apiService)

                val result = tableReadRepository.refreshTables(token)
                result.fold(
                    onSuccess = {
                        _refreshWarning.value = null
                    },
                    onFailure = { error ->
                        handleRefreshError(error, hasCachedData)
                    }
                )
            } catch (e: Exception) {
                handleRefreshError(e, hasCachedData)
            } finally {
                _isLoading.value = false
                _isRefreshing.value = false
                isRefreshingNetwork = false
            }
        }
    }

    private fun handleRefreshError(error: Throwable, hasCachedData: Boolean) {
        if (error is HttpException) {
            when (error.code()) {
                401 -> {
                    _sessionExpired.value = true
                    _error.value = "Sessão expirada. Faça login novamente."
                    return
                }
                403 -> {
                    _error.value = "Terminal bloqueado. Contate o suporte."
                    return
                }
                426 -> {
                    _error.value = "Atualização obrigatória do aplicativo necessária."
                    return
                }
            }
        }

        if (hasCachedData) {
            _refreshWarning.value = "Sem conexão — exibindo dados salvos"
        } else {
            _error.value = when (error) {
                is java.io.IOException -> "Erro de conexão ao carregar mesas"
                is com.google.gson.JsonParseException -> "Erro de compatibilidade nos dados de mesas"
                is HttpException -> "Erro no servidor (Código: ${error.code()})"
                else -> "Erro ao carregar mesas: ${error.localizedMessage}"
            }
        }
    }

    fun openTable(token: String, table: Table, customerName: String) {
        if (!table.comandaId.isNullOrEmpty()) {
            _openedComandaId.value = table.comandaId
            _openSuccess.value = true
            return
        }

        val request = CommandActionRequest().apply {
            action = "abrir"
            mesaId = table.id
            people_count = 1
            nome_cliente = customerName
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                val response = retryIO { apiService.manageComanda("Bearer $token", request) }
                if (response.isSuccessful) {
                    val body = response.body()
                    val comandaId = body?.get("id") as? String
                    Log.d("MesaViewModel", "Mesa aberta com sucesso. ComandaId: $comandaId")
                    if (!comandaId.isNullOrEmpty()) {
                        _openedComandaId.value = comandaId
                        _openSuccess.value = true
                    } else {
                        Log.e("MesaViewModel", "API retornou sucesso mas o ID da comanda veio vazio: $body")
                        _error.value = "Erro: ID da comanda não retornado pela API"
                    }
                } else {
                    val errorCode = response.code()
                    val errorBody = response.errorBody()?.string() ?: ""
                    Log.e("MesaViewModel", "Falha ao abrir mesa: $errorCode - $errorBody")
                    if (errorCode == 401) {
                        _error.value = "Sessão expirada. Por favor, faça login novamente."
                    } else {
                        _error.value = "Erro ao abrir mesa (Código: $errorCode)"
                    }
                }
            } catch (e: java.io.IOException) {
                _error.value = "Sem conexão. Os dados salvos podem ser consultados, mas esta ação requer conexão."
            } catch (e: Exception) {
                Log.e("MesaViewModel", "Failed to open table due to exception", e)
                _error.value = "Erro ao abrir mesa: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun consumeOpenSuccess() {
        _openSuccess.value = false
        _openedComandaId.value = null
    }

    fun transferTable(token: String, origin: Table, destination: Table) {
        val comandaId = origin.comandaId ?: return
        
        // Optimistic UI mirror
        val currentTables = _tables.value?.toMutableList() ?: mutableListOf()
        val originInList = currentTables.find { it.id == origin.id }
        val destInList = currentTables.find { it.id == destination.id }
        
        if (originInList != null && destInList != null) {
            destInList.status = Table.Status.OCCUPIED
            destInList.comandaId = originInList.comandaId
            destInList.customerName = originInList.customerName
            destInList.items = originInList.items.toMutableList()
            
            originInList.status = Table.Status.AVAILABLE
            originInList.comandaId = null
            originInList.customerName = ""
            originInList.items = mutableListOf()
            
            _tables.value = currentTables
            TableManager.setTables(currentTables)
        }

        val request = CommandActionRequest().apply {
            action = "transferir_mesa"
            this.comandaId = comandaId
            this.destinationTableId = destination.id
        }

        viewModelScope.launch {
            try {
                val response = apiService.manageComanda("Bearer $token", request)
                if (!response.isSuccessful) {
                    queueManager.addToQueue(token, request)
                }
            } catch (e: Exception) {
                queueManager.addToQueue(token, request)
                Log.e("MesaViewModel", "Network error during transfer, queued", e)
            }
        }
    }
}
