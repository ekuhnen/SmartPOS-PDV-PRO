package com.plugpdv.pdv.ui.sale

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.utils.retryIO
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

import android.content.Context
import com.plugpdv.pdv.utils.Constants
import dagger.hilt.android.qualifiers.ApplicationContext

@HiltViewModel
class CommandViewModel @Inject constructor(
    private val apiService: PosApiService,
    private val catalogDao: com.plugpdv.pdv.database.CatalogDao,
    @ApplicationContext private val context: Context
) : ViewModel() {

    private val _comanda = MutableLiveData<ComandaDetailResponse?>(null)
    val comanda: LiveData<ComandaDetailResponse?> = _comanda

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private val _notFound = MutableLiveData<String?>(null)
    val notFound: LiveData<String?> = _notFound

    private val _items = MutableLiveData<List<TableItem>>(emptyList())
    val items: LiveData<List<TableItem>> = _items

    private val _openFinished = MutableLiveData<String?>(null)
    val openFinished: LiveData<String?> = _openFinished

    fun clearNotFound() {
        _notFound.value = null
    }

    fun fetchComanda(token: String, code: String) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                _error.value = null
                _notFound.value = null
                
                val response = retryIO { apiService.getComandaDetail("Bearer $token", code) }
                _comanda.value = response
                
                // Map DTO items to TableItem for reuse in UI
                val uiItems = mutableListOf<TableItem>()
                val filteredItems = response.itens.filter { it.status != "CANCELADO" && it.status != "REMOVIDO" }
                val groupedItems = filteredItems.groupBy { Pair(it.nestedProduct?.id ?: it.produto_id, it.observacao) }

                groupedItems.forEach { (groupKey, itemDtos) ->
                    val pId = groupKey.first ?: return@forEach
                    val obs = groupKey.second
                    val firstDto = itemDtos.first()
                    val serverQty = itemDtos.sumOf { it.quantidade ?: 0 }

                    val localProduct = catalogDao.getProductById(pId)

                    var productName = localProduct?.name
                    if (productName.isNullOrEmpty()) {
                        productName = firstDto.nestedProduct?.name ?: firstDto.nome
                    }

                    var productPrice = localProduct?.selling_price
                    if (productPrice == null || productPrice == 0.0) {
                        val rawPrice = if (firstDto.nestedProduct?.selling_price != null && firstDto.nestedProduct?.selling_price != 0.0) {
                            firstDto.nestedProduct?.selling_price ?: 0.0
                        } else {
                            firstDto.preco_unitario ?: 0.0
                        }
                        val currency = firstDto.nestedProduct?.price_currency ?: com.plugpdv.pdv.utils.CurrencyManager.getInstance().getBaseCurrency()
                        productPrice = com.plugpdv.pdv.utils.CurrencyManager.getInstance().toBrl(rawPrice, currency)
                    }

                    val fakeProduct = Product(
                        id = pId,
                        name = productName,
                        selling_price = productPrice ?: 0.0
                    )
                    uiItems.add(TableItem(product = fakeProduct, quantity = serverQty).apply {
                        id = firstDto.id
                        serverIds = itemDtos.mapNotNull { it.id }.toMutableList()
                        observation = obs
                    })
                }
                _items.value = uiItems
                
            } catch (e: Exception) {
                Log.e("CommandViewModel", "Failed to fetch comanda", e)
                if (e is retrofit2.HttpException && e.code() == 404) {
                    try {
                        val codeInt = code.toIntOrNull()
                        
                        // 1. Try to search in the active comandas list first
                        val comandasList = retryIO { apiService.getComandasList("Bearer $token") }
                        val foundComanda = comandasList.comandas.find {
                            it.numero == codeInt || it.nomeCliente?.equals(code, ignoreCase = true) == true || it.id.equals(code, ignoreCase = true)
                        }
                        if (foundComanda != null) {
                            fetchComanda(token, foundComanda.id)
                            return@launch
                        }

                        // 2. Fallback to searching inside tables/mesas list
                        val mesasResponse = retryIO { apiService.getMesas("Bearer $token") }
                        val foundMesa = mesasResponse.setores.orEmpty().flatMap { it.mesas.orEmpty() }.find { 
                            it.numero == codeInt || it.nome_cliente?.equals(code, ignoreCase = true) == true || it.comanda_id?.equals(code, ignoreCase = true) == true
                        }
                        if (foundMesa != null && !foundMesa.comanda_id.isNullOrEmpty()) {
                            fetchComanda(token, foundMesa.comanda_id)
                            return@launch
                        }
                    } catch (e2: Exception) {
                        Log.e("CommandViewModel", "Fallback search failed", e2)
                    }
                    _notFound.value = code
                } else {
                    _error.value = "Erro ao carregar comanda"
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun openComanda(token: String, code: String, nickname: String) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val operatorId = prefs.getString(Constants.OPERATOR_ID, null)

        val request = CommandActionRequest().apply {
            action = "abrir"
            comandaId = null // Let server auto-generate UUID to prevent type-cast errors on number strings
            id = null        // Let server auto-generate UUID
            mesaId = null
            people_count = 1
            waiterId = operatorId
            numero = code.toIntOrNull()
            nome_cliente = nickname.takeIf { it.isNotEmpty() } ?: code
            customerName = nickname.takeIf { it.isNotEmpty() } ?: code
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                _notFound.value = null
                val response = apiService.manageComanda("Bearer $token", request)
                if (response.isSuccessful) {
                    // Check if server returned a real ID/UUID
                    val responseBody = response.body()
                    val serverId = responseBody?.get("id")?.toString() ?: 
                                  responseBody?.get("comanda_id")?.toString() ?: code
                    
                    _openFinished.value = serverId
                } else {
                    val errorBody = response.errorBody()?.string() ?: "Sem detalhes"
                    _error.value = "API recusou (Status ${response.code()}): $errorBody"
                }
            } catch (e: Exception) {
                _error.value = "Erro de conexão: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearOpenFinished() {
        _openFinished.value = null
    }

    fun addItemToComanda(token: String, code: String, product: Product, quantity: Int, observation: String?) {
        val request = CommandActionRequest().apply {
            action = "add_item"
            comandaId = code
            mesaId = null
            this.product_id = product.id
            this.quantity = quantity
            this.itemObservation = observation
        }

        // Optimistic update
        val currentItems = _items.value?.toMutableList() ?: mutableListOf()
        val existing = currentItems.find { it.product.id == product.id && !it.removed }
        if (existing != null) {
            existing.quantity += quantity
        } else {
            currentItems.add(TableItem(product = product, quantity = quantity).apply {
                this.observation = observation
            })
        }
        _items.value = currentItems

        viewModelScope.launch {
            try {
                // _isLoading.value = true // Don't block UI for optimistic add
                val response = apiService.manageComanda("Bearer $token", request)
                if (response.isSuccessful) {
                    fetchComanda(token, code)
                } else {
                    _error.value = "Erro ao sincronizar item (Status: ${response.code()})"
                    fetchComanda(token, code) // Revert/Refresh
                }
            } catch (e: Exception) {
                _error.value = "Erro de rede ao adicionar item"
                // No need to revert immediate UI if we want to be "offline-capable", 
                // but for now let's just refresh to match server State
                fetchComanda(token, code)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun removeItemFromComanda(token: String, code: String, item: TableItem, reason: String) {
        val request = CommandActionRequest().apply {
            action = "cancel_item"
            comandaId = code
            order_id = item.id
            product_id = item.product.id
            this.reason = reason
            itemIds = item.serverIds
        }

        val currentItems = _items.value?.toMutableList() ?: mutableListOf()
        currentItems.find { it.id == item.id }?.removed = true
        _items.value = currentItems

        viewModelScope.launch {
            try {
                val response = apiService.manageComanda("Bearer $token", request)
                if (response.isSuccessful) {
                    fetchComanda(token, code)
                } else {
                    _error.value = "Erro ao remover item (Status: ${response.code()})"
                    fetchComanda(token, code)
                }
            } catch (e: Exception) {
                _error.value = "Erro de rede ao remover item"
                fetchComanda(token, code)
            }
        }
    }

    fun updateItemObservation(token: String, code: String, item: TableItem, observation: String) {
        val request = CommandActionRequest().apply {
            action = "update_item"
            comandaId = code
            order_id = item.id
            product_id = item.product.id
            this.itemObservation = observation
        }

        val currentItems = _items.value?.toMutableList() ?: mutableListOf()
        currentItems.find { it.id == item.id }?.observation = observation
        _items.value = currentItems

        viewModelScope.launch {
            try {
                val response = apiService.manageComanda("Bearer $token", request)
                if (response.isSuccessful) {
                    fetchComanda(token, code)
                } else {
                    // Fallback se a API não suportar update_item silenciosamente
                    fetchComanda(token, code)
                }
            } catch (e: Exception) {
                fetchComanda(token, code)
            }
        }
    }
}
