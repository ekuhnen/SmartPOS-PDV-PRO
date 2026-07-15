package com.plugpdv.pdv.ui.sale

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.models.CommandActionRequest
import com.plugpdv.pdv.models.Product
import com.plugpdv.pdv.models.Sector
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.models.TableItem
import com.plugpdv.pdv.utils.TableManager
import com.plugpdv.pdv.utils.retryIO
import com.plugpdv.pdv.utils.TransferQueueManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.content.Context

@HiltViewModel
class MesaViewModel @Inject constructor(
    private val apiService: PosApiService,
    private val catalogDao: com.plugpdv.pdv.database.CatalogDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val queueManager = TransferQueueManager(context)


    private val _tables = MutableLiveData<List<Table>>(emptyList())
    
    private val _sectors = MutableLiveData<List<Sector>>(emptyList())
    val sectors: LiveData<List<Sector>> = _sectors

    private val _selectedSectorId = MutableLiveData<String?>(null)
    val selectedSectorId: LiveData<String?> = _selectedSectorId

    private val _filteredTables = MediatorLiveData<List<Table>>().apply {
        addSource(_tables) { updateFilteredTables() }
        addSource(_selectedSectorId) { updateFilteredTables() }
    }
    val tables: LiveData<List<Table>> = _filteredTables

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

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _openSuccess = MutableLiveData(false)
    val openSuccess: LiveData<Boolean> = _openSuccess

    fun fetchTables(token: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                
                // Process any pending transfers first
                queueManager.processQueue(apiService)
                
                val response = retryIO { apiService.getMesas("Bearer $token") }
                
                val uiSectors = mutableListOf<Sector>()
                uiSectors.add(Sector(id = "", nome = "Todos"))
                uiSectors.addAll(response.setores.orEmpty())
                _sectors.value = uiSectors

                val newTables = response.setores.orEmpty().flatMap { sector ->
                    sector.mesas.orEmpty().map { mesaDto ->
                        Table(number = mesaDto.numero).apply {
                            id = mesaDto.id
                            sectorName = sector.nome
                            sectorId = sector.id
                            status = mapStatus(mesaDto.status)
                            if (status == Table.Status.OCCUPIED && mesaDto.comanda_id != null) {
                                comandaId = mesaDto.comanda_id
                                people_count = mesaDto.pessoas_qtd
                                mesaDto.itens?.forEach { itemDto ->
                                    var productName = itemDto.nestedProduct?.name ?: itemDto.nome
                                    var productPrice = if (itemDto.nestedProduct?.selling_price != null && itemDto.nestedProduct.selling_price != 0.0) {
                                        itemDto.nestedProduct.selling_price
                                    } else {
                                        itemDto.preco_unitario
                                    }

                                    val prodId = itemDto.produto_id.orEmpty()
                                    if (productName.isNullOrEmpty() || productPrice == 0.0) {
                                        val localProduct = if (prodId.isNotEmpty()) catalogDao.getProductById(prodId) else null
                                        if (localProduct != null) {
                                            if (productName.isNullOrEmpty()) productName = localProduct.name
                                            if (productPrice == 0.0) productPrice = localProduct.selling_price
                                        }
                                    }

                                    val fakeProduct = Product(
                                        id = prodId,
                                        name = productName,
                                        selling_price = productPrice
                                    )
                                    val item = TableItem(product = fakeProduct, quantity = itemDto.quantidade).apply {
                                        id = itemDto.id
                                        observation = itemDto.observacao
                                        if (itemDto.status == "REMOVIDO" || itemDto.status == "CANCELADO") {
                                            removed = true
                                        }
                                    }
                                    items.add(item)
                                }
                                calculateTotal()
                            }
                        }
                    }
                }
                _tables.value = newTables
                TableManager.setTables(newTables)
            } catch (e: Exception) {
                Log.e("MesaViewModel", "Failed to fetch tables", e)
                _error.value = "Erro ao carregar mesas"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun mapStatus(status: String?): String {
        return when (status) {
            "OCUPADA" -> Table.Status.OCCUPIED
            "BLOQUEADA" -> Table.Status.RESERVED
            else -> Table.Status.AVAILABLE
        }
    }

    fun openTable(token: String, table: Table, customerName: String) {
        val request = CommandActionRequest().apply {
            action = "abrir"
            mesaId = table.id
            people_count = 1 // Default to 1 if not specified
            observation = customerName // Mapping customerName to observation
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                retryIO { apiService.manageComanda("Bearer $token", request) }
                _openSuccess.value = true
                fetchTables(token)
            } catch (e: Exception) {
                _error.value = "Erro ao abrir mesa"
            } finally {
                _isLoading.value = false
                _openSuccess.value = false
            }
        }
    }

    fun transferTable(token: String, origin: Table, destination: Table) {
        val comandaId = origin.comandaId ?: return
        
        // 1. Optimistic Update
        val currentTables = _tables.value?.toMutableList() ?: mutableListOf()
        
        val originInList = currentTables.find { it.id == origin.id }
        val destInList = currentTables.find { it.id == destination.id }
        
        if (originInList != null && destInList != null) {
            // Transfer data
            destInList.status = Table.Status.OCCUPIED
            destInList.comandaId = originInList.comandaId
            destInList.customerName = originInList.customerName
            destInList.total = originInList.total
            destInList.paidAmount = originInList.paidAmount
            destInList.items = originInList.items.toMutableList()
            
            // Clear origin
            originInList.status = Table.Status.AVAILABLE
            originInList.comandaId = null
            originInList.customerName = ""
            originInList.total = 0.0
            originInList.paidAmount = 0.0
            originInList.items = mutableListOf()
            
            _tables.value = currentTables
            TableManager.setTables(currentTables)
        }

        // 2. Prepare API call
        val request = CommandActionRequest().apply {
            action = "transferir_mesa"
            this.comandaId = comandaId
            this.destinationTableId = destination.id
        }

        viewModelScope.launch {
            try {
                val response = apiService.manageComanda("Bearer $token", request)
                if (!response.isSuccessful) {
                    // If server error (not network), maybe retry or queue? 
                    // specification says "proteger em caso de sinal fraco", so queue it.
                    queueManager.addToQueue(token, request)
                }
            } catch (e: Exception) {
                // Network error, queue it
                queueManager.addToQueue(token, request)
                Log.e("MesaViewModel", "Network error during transfer, queued", e)
            }
        }
    }
}
