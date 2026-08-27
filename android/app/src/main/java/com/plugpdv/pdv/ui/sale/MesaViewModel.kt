package com.plugpdv.pdv.ui.sale

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.MediatorLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.models.CommandActionRequest
import com.plugpdv.pdv.models.MesaItemDto
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

    private val _openedComandaId = MutableLiveData<String?>(null)
    val openedComandaId: LiveData<String?> = _openedComandaId

    private val _sessionExpired = MutableLiveData<Boolean>(false)
    val sessionExpired: LiveData<Boolean> = _sessionExpired

    fun consumeSessionExpired() {
        _sessionExpired.value = false
    }

    fun fetchTables(token: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                
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
                            sectorName = sector.nome.orEmpty()
                            sectorId = sector.id.orEmpty()
                            status = mapStatus(mesaDto.status, mesaDto.comanda_id, mesaDto.itens)
                            if ((status == Table.Status.OCCUPIED || !mesaDto.comanda_id.isNullOrEmpty()) && mesaDto.comanda_id != null) {
                                comandaId = mesaDto.comanda_id
                                people_count = mesaDto.pessoas_qtd ?: 1
                                mesaDto.itens?.forEach { itemDto ->
                                    val prodId = (itemDto.produto_id ?: itemDto.nestedProduct?.id).orEmpty()
                                    val localProduct = if (prodId.isNotEmpty()) catalogDao.getProductById(prodId) else null

                                    var productName = localProduct?.name
                                    if (productName.isNullOrEmpty()) {
                                        productName = itemDto.nestedProduct?.name ?: itemDto.nome
                                    }

                                    var productPrice = localProduct?.selling_price
                                    if (productPrice == null || productPrice == 0.0) {
                                         val rawPrice = if (itemDto.nestedProduct?.selling_price != null && itemDto.nestedProduct?.selling_price != 0.0) {
                                             itemDto.nestedProduct?.selling_price ?: 0.0
                                         } else if (itemDto.preco_unitario != null && itemDto.preco_unitario != 0.0) {
                                             itemDto.preco_unitario
                                         } else {
                                             itemDto.subtotal ?: 0.0
                                         }
                                         val currency = itemDto.nestedProduct?.price_currency ?: com.plugpdv.pdv.utils.CurrencyManager.getInstance().getBaseCurrency()
                                         productPrice = com.plugpdv.pdv.utils.CurrencyManager.getInstance().toBrl(rawPrice, currency)
                                    }

                                    val fakeProduct = Product(
                                        id = prodId,
                                        name = productName,
                                        selling_price = productPrice ?: 0.0
                                    )
                                    val item = TableItem(product = fakeProduct, quantity = itemDto.quantidade ?: 1).apply {
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
            } catch (e: retrofit2.HttpException) {
                Log.e("MesaViewModel", "HTTP error fetching tables: ${e.code()} ${e.message()}", e)
                if (e.code() == 401) {
                    _sessionExpired.value = true
                } else {
                    _error.value = "Erro no servidor (Código: ${e.code()})"
                }
            } catch (e: java.io.IOException) {
                Log.e("MesaViewModel", "Network error fetching tables", e)
                _error.value = "Erro de conexão ao carregar mesas"
            } catch (e: com.google.gson.JsonParseException) {
                Log.e("MesaViewModel", "JSON parse error fetching tables", e)
                _error.value = "Erro de compatibilidade nos dados de mesas"
            } catch (e: Exception) {
                Log.e("MesaViewModel", "Unexpected error fetching tables", e)
                _error.value = "Erro ao carregar mesas: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun mapStatus(status: String?, comandaId: String?, items: List<MesaItemDto>?): String {
        val upper = status?.uppercase() ?: ""
        if (upper.contains("OCUPADA") || upper.contains("CONSUMO") || upper.contains("PAGAMENTO") || upper.contains("BUSY") || upper.contains("OCCUPIED")) {
            return Table.Status.OCCUPIED
        }
        if (upper.contains("BLOQUEADA") || upper.contains("RESERVADA") || upper.contains("RESERVED")) {
            return Table.Status.RESERVED
        }
        if (!comandaId.isNullOrEmpty() || (!items.isNullOrEmpty() && items.any { it.status != "CANCELADO" && it.status != "REMOVIDO" })) {
            return Table.Status.OCCUPIED
        }
        return Table.Status.AVAILABLE
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
            } catch (e: Exception) {
                Log.e("MesaViewModel", "Failed to open table due to exception", e)
                _error.value = "Erro ao abrir mesa: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    /** Consome o evento de sucesso para evitar re-navegação em recriações do Fragment */
    fun consumeOpenSuccess() {
        _openSuccess.value = false
        _openedComandaId.value = null
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
