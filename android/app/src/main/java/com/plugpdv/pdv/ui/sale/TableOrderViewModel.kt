package com.plugpdv.pdv.ui.sale

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.CatalogDao
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.utils.TableManager
import com.plugpdv.pdv.utils.retryIO
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TableOrderViewModel @Inject constructor(
    private val apiService: PosApiService,
    private val catalogDao: CatalogDao
) : ViewModel() {

    private val _table = MutableLiveData<Table?>()
    val table: LiveData<Table?> = _table

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>(null)
    val error: LiveData<String?> = _error

    private var token: String? = null

    private val pendingAdditions = mutableMapOf<String, Int>()
    private val previousServerQuantities = mutableMapOf<String, Int>()

    fun init(table: Table, token: String) {
        this._table.value = table
        this.token = token
        // Reset sync trackers when opening a new table
        pendingAdditions.clear()
        previousServerQuantities.clear()
        syncTable()
    }

    fun syncTable() {
        val currentTable = _table.value ?: return
        val currentToken = token ?: return
        if (currentTable.id.isNullOrEmpty()) return

        viewModelScope.launch {
            try {
                _isLoading.value = true
                
                val cId = currentTable.comandaId
                if (!cId.isNullOrEmpty()) {
                    // Optimized sync using specific comanda detail
                    val response = retryIO { apiService.getComandaDetail("Bearer $currentToken", cId) }
                    
                    currentTable.apply {
                        // Preserve existing paid state locally
                        val oldPaidAmount = paidAmount
                        val oldPaidQuantities = items.associate { it.product.id to it.paidQuantity }
                        val oldIsPaid = items.associate { it.product.id to it.isPaid }

                        // 1. Update from remote source of truth
                        val serverPaid = if (response.totalPago > 0) response.totalPago else response.pagamentos.sumOf { it.valor }
                        paidAmount = if (serverPaid > 0) serverPaid else oldPaidAmount

                        items.clear()
                        
                        val seenProducts = mutableSetOf<String>()
                        
                        val filteredItems = response.itens.filter { it.status != "CANCELADO" && it.status != "REMOVIDO" }
                        val groupedItems = filteredItems.groupBy { Pair(it.nestedProduct?.id ?: it.produto_id, it.observacao) }

                        groupedItems.forEach { (groupKey, itemDtos) ->
                            val pId = groupKey.first ?: return@forEach
                            val obs = groupKey.second
                            seenProducts.add(pId)
                            
                            val serverQty = itemDtos.sumOf { it.quantidade }
                            val trackingKey = pId + (obs ?: "")
                            val prevQty = previousServerQuantities[trackingKey] ?: 0
                            
                            if (serverQty > prevQty) {
                                val consumed = serverQty - prevQty
                                pendingAdditions[pId] = (pendingAdditions[pId] ?: 0).minus(consumed).coerceAtLeast(0)
                            }
                            previousServerQuantities[trackingKey] = serverQty
                            
                            val finalQty = serverQty + (if (obs == null) pendingAdditions[pId] ?: 0 else 0)
                            
                            val firstDto = itemDtos.first()
                            var productName = firstDto.nestedProduct?.name ?: firstDto.nome
                            var productPrice = if (firstDto.nestedProduct?.selling_price != 0.0) firstDto.nestedProduct?.selling_price else firstDto.preco_unitario

                            // Fallback to local database if names/prices are missing from API
                            if (productName.isNullOrEmpty() || productPrice == null || productPrice == 0.0) {
                                val localProduct = catalogDao.getProductById(pId)
                                if (localProduct != null) {
                                    if (productName.isNullOrEmpty()) productName = localProduct.name
                                    if (productPrice == null || productPrice == 0.0) productPrice = localProduct.selling_price
                                }
                            }

                            val product = Product(
                                id = pId, 
                                name = productName, 
                                selling_price = productPrice
                            )
                            val item = TableItem(product = product, quantity = finalQty).apply {
                                id = firstDto.id
                                serverIds = itemDtos.mapNotNull { it.id }.toMutableList()
                                observation = obs
                                paidQuantity = oldPaidQuantities[pId] ?: 0
                                isPaid = oldIsPaid[pId] ?: false
                            }
                            items.add(item)
                        }
                        calculateTotal()
                    }
                } else {
                    // Fallback to getMesas if no comandaId yet
                    val response = apiService.getMesas("Bearer $currentToken")
                    val updatedTable = response.setores.orEmpty().flatMap { it.mesas.orEmpty() }
                        .find { it.id == currentTable.id }
                    
                    updatedTable?.let { dto ->
                        currentTable.apply {
                            val oldPaidAmount = paidAmount
                            val oldPaidQuantities = items.associate { it.product.id to it.paidQuantity }
                            val oldIsPaid = items.associate { it.product.id to it.isPaid }

                            comandaId = dto.comanda_id
                            items.clear()
                            paidAmount = oldPaidAmount

                            val filteredItems = dto.itens?.filter { it.status != "CANCELADO" && it.status != "REMOVIDO" } ?: emptyList()
                            val groupedItems = filteredItems.groupBy { Pair(it.nestedProduct?.id ?: it.produto_id, it.observacao) }

                            groupedItems.forEach { (groupKey, itemDtos) ->
                                val pId = groupKey.first ?: return@forEach
                                val obs = groupKey.second
                                val firstDto = itemDtos.first()
                                val serverQty = itemDtos.sumOf { it.quantidade }

                                var productName = firstDto.nestedProduct?.name ?: firstDto.nome
                                var productPrice = if (firstDto.nestedProduct?.selling_price != 0.0) firstDto.nestedProduct?.selling_price else firstDto.preco_unitario

                                if (productName.isNullOrEmpty() || productPrice == null || productPrice == 0.0) {
                                    val localProduct = catalogDao.getProductById(pId)
                                    if (localProduct != null) {
                                        if (productName.isNullOrEmpty()) productName = localProduct.name
                                        if (productPrice == null || productPrice == 0.0) productPrice = localProduct.selling_price
                                    }
                                }

                                val product = Product(
                                    id = pId, 
                                    name = productName, 
                                    selling_price = productPrice
                                )
                                items.add(TableItem(product = product, quantity = serverQty).apply { 
                                    id = firstDto.id 
                                    serverIds = itemDtos.mapNotNull { it.id }.toMutableList()
                                    observation = obs
                                    paidQuantity = oldPaidQuantities[pId] ?: 0
                                    isPaid = oldIsPaid[pId] ?: false
                                })
                            }
                            calculateTotal()
                        }
                    }
                }
                
                TableManager.updateTable(currentTable)
                _table.value = currentTable
            } catch (e: Exception) {
                Log.e("TableOrderViewModel", "Sync failed", e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun addItem(product: Product) {
        val currentTable = _table.value ?: return
        val currentToken = token ?: return
        val cId = currentTable.comandaId

        if (cId.isNullOrEmpty()) {
            _error.value = "ID da comanda não encontrado"
            return
        }

        // 1. Record pending addition
        pendingAdditions[product.id] = (pendingAdditions[product.id] ?: 0) + 1

        // 2. Optimistic Update (Immediate UI feedback)
        val existing = currentTable.items.find { !it.removed && it.product.id == product.id }
        if (existing != null) {
            existing.quantity++
        } else {
            currentTable.items.add(TableItem(product = product, quantity = 1))
        }
        currentTable.calculateTotal()
        _table.value = currentTable

        val request = CommandActionRequest().apply {
            action = "add_item"
            mesaId = currentTable.id
            comandaId = cId
            product_id = product.id
            quantity = 1
            status = "RASCUNHO"
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                retryIO { apiService.manageComanda("Bearer $currentToken", request) }
                
                // After success, wait a bit for server persistence before syncing
                viewModelScope.launch {
                    kotlinx.coroutines.delay(1000)
                    syncTable()
                }
            } catch (e: Exception) {
                // Revert pending on error
                pendingAdditions[product.id] = (pendingAdditions[product.id] ?: 0).minus(1).coerceAtLeast(0)
                _error.value = "Erro ao adicionar item"
                syncTable() // Refresh to real state
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun removeItem(item: TableItem, reason: String) {
        val currentTable = _table.value ?: return
        val currentToken = token ?: return

        val request = CommandActionRequest().apply {
            action = "cancel_item"
            mesaId = currentTable.id
            comandaId = currentTable.comandaId
            order_id = item.id
            product_id = item.product.id
            this.reason = reason
            itemIds = item.serverIds
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                retryIO { apiService.manageComanda("Bearer $currentToken", request) }
                item.removed = true
                item.removalReason = reason
                currentTable.calculateTotal()
                _table.value = currentTable
            } catch (e: Exception) {
                _error.value = "Erro ao remover item"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun enviarCozinha(onSuccess: () -> Unit) {
        val currentTable = _table.value ?: return
        val currentToken = token ?: return
        val cId = currentTable.comandaId

        if (cId.isNullOrEmpty()) {
            _error.value = "ID da comanda não encontrado"
            return
        }

        val request = CommandActionRequest().apply {
            action = "enviar_cozinha"
            comandaId = cId
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                retryIO { apiService.manageComanda("Bearer $currentToken", request) }
                onSuccess()
            } catch (e: Exception) {
                _error.value = "Erro ao enviar pedido para a cozinha"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
