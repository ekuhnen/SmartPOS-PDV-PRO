package com.plugpdv.pdv.ui.sale

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.R
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.utils.retryIO
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

import android.content.Context
import com.plugpdv.pdv.utils.ComandaItemHydrator
import com.plugpdv.pdv.utils.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.plugpdv.pdv.realtime.RestaurantReadSessionGuard
import com.plugpdv.pdv.repository.RestaurantOpsRepository
import com.plugpdv.pdv.models.ComandaDiscoveryItem

internal data class CommandItemMoney(val price: Double?, val currency: String?)

internal object CommandItemMoneyMapper {
    fun fromAuthoritativeDetail(item: MesaItemDto, quantity: Int, baseCurrency: String?): CommandItemMoney =
        CommandItemMoney(
            price = ComandaItemHydrator.authoritativeUnitPrice(item, quantity),
            currency = baseCurrency?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }
        )
}

@HiltViewModel
class CommandViewModel @Inject constructor(
    private val apiService: PosApiService,
    private val catalogDao: com.plugpdv.pdv.database.CatalogDao,
    @ApplicationContext private val context: Context,
    private val restaurantOpsRepository: RestaurantOpsRepository
) : ViewModel() {
    private val readMutex = Mutex()

    suspend fun refreshRestaurantRead(token: String, code: String) {
        readComanda(token, _comanda.value?.id ?: code, allowLookup = false)
    }

    private fun localized(@androidx.annotation.StringRes resource: Int, fallbackCode: String, vararg args: Any): String =
        runCatching { context.getString(resource, *args) }.getOrDefault(fallbackCode)

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
    private val _searchResults = MutableLiveData<List<ComandaDiscoveryItem>?>(null)
    val searchResults: LiveData<List<ComandaDiscoveryItem>?> = _searchResults

    fun clearNotFound() {
        _notFound.value = null
    }

    fun clearSearchResults() { _searchResults.value = null }

    fun searchOperational(token: String, query: String) {
        val normalized = query.trim()
        if (normalized.matches(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}"))) {
            fetchComanda(token, normalized)
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            restaurantOpsRepository.search(token, normalized).fold(
                onSuccess = { _searchResults.value = it },
                onFailure = { _error.value = localized(R.string.load_comanda_error, "LOAD_COMANDA_ERROR") }
            )
            _isLoading.value = false
        }
    }

    fun fetchComanda(token: String, code: String) {
        viewModelScope.launch { readComanda(token, code) }
    }

    private suspend fun readComanda(token: String, code: String, allowLookup: Boolean = true) {
        readMutex.withLock {
            val sessionGuard = RestaurantReadSessionGuard(context, token)
            try {
                sessionGuard.check()
                _isLoading.value = true
                _error.value = null
                _notFound.value = null
                
                val response = retryIO { apiService.getComandaDetail("Bearer $token", code) }
                sessionGuard.check()
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

                    val authoritativeMoney = CommandItemMoneyMapper.fromAuthoritativeDetail(
                        firstDto,
                        serverQty,
                        response.baseCurrency
                    )

                    val fakeProduct = Product(
                        id = pId,
                        name = productName,
                        selling_price = authoritativeMoney.price,
                        price_currency = authoritativeMoney.currency
                    )
                    uiItems.add(TableItem(product = fakeProduct, quantity = serverQty).apply {
                        id = firstDto.id
                        serverIds = itemDtos.mapNotNull { it.id }.toMutableList()
                        observation = obs
                    })
                }
                sessionGuard.check()
                _items.value = uiItems
                
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("CommandViewModel", "Failed to fetch comanda", e)
                if (allowLookup && e is retrofit2.HttpException && e.code() == 404) {
                    try {
                        val codeInt = code.toIntOrNull()
                        
                        // 1. Try to search in the active comandas list first
                        val comandasList = retryIO { apiService.getComandasList("Bearer $token") }
                        val numericMatches = codeInt?.let { n -> comandasList.comandas.filter { it.numero == n } }.orEmpty()
                        val nameMatches = comandasList.comandas.filter { it.nomeCliente?.equals(code, ignoreCase = true) == true }
                        val idMatches = comandasList.comandas.filter { it.id.equals(code, ignoreCase = true) }
                        val matches = (numericMatches + nameMatches + idMatches).distinctBy { it.id }
                        if (matches.size == 1) {
                            fetchComanda(token, matches.single().id)
                            return@withLock
                        }

                        // 2. Fallback to searching inside tables/mesas list
                        val mesasResponse = retryIO { apiService.getMesas("Bearer $token") }
                        val mesaMatches = mesasResponse.setores.orEmpty().flatMap { it.mesas.orEmpty() }.filter { mesa ->
                            (codeInt != null && mesa.numero == codeInt) ||
                                mesa.nome_cliente?.equals(code, ignoreCase = true) == true ||
                                mesa.comanda_id?.equals(code, ignoreCase = true) == true
                        }
                        val mesaComandaIds = mesaMatches.mapNotNull { it.comanda_id }.distinct()
                        if (mesaComandaIds.size == 1) {
                            fetchComanda(token, mesaComandaIds.single())
                            return@withLock
                        }
                    } catch (e2: Exception) {
                        Log.e("CommandViewModel", "Fallback search failed", e2)
                    }
                    _notFound.value = code
                } else if (e is retrofit2.HttpException && e.code() == 403) {
                    val errorBody = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                    _error.value = com.plugpdv.pdv.utils.HttpErrorParser.parse403Message(errorBody, defaultMode = "comanda")
                } else if (e is retrofit2.HttpException && e.code() == 401) {
                    _error.value = localized(R.string.session_expired, "SESSION_EXPIRED")
                } else {
                    _error.value = localized(R.string.load_comanda_error, "LOAD_COMANDA_ERROR")
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
                    if (response.code() == 403) {
                        _error.value = com.plugpdv.pdv.utils.HttpErrorParser.parse403Message(errorBody, defaultMode = "comanda")
                    } else if (response.code() == 401) {
                    _error.value = localized(R.string.session_expired, "SESSION_EXPIRED")
                    } else {
                    Log.w("CommandViewModel", "API rejected status ${response.code()}: $errorBody")
                    _error.value = localized(R.string.api_rejected_status, "API_REJECTED", response.code())
                    }
                }
            } catch (e: Exception) {
                _error.value = localized(R.string.connection_error_detail, "CONNECTION_ERROR", e.message.orEmpty())
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
                    _error.value = localized(R.string.sync_item_error_status, "SYNC_ITEM_ERROR", response.code())
                    fetchComanda(token, code) // Revert/Refresh
                }
            } catch (e: Exception) {
                _error.value = localized(R.string.add_item_network_error, "ADD_ITEM_NETWORK_ERROR")
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
                    _error.value = localized(R.string.remove_item_error_status, "REMOVE_ITEM_ERROR", response.code())
                    fetchComanda(token, code)
                }
            } catch (e: Exception) {
                _error.value = localized(R.string.remove_item_network_error, "REMOVE_ITEM_NETWORK_ERROR")
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
