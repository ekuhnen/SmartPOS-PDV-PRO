package com.plugpdv.pdv.ui.sale

import android.content.Context
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.CatalogDao
import com.plugpdv.pdv.database.ComandaSnapshotEntity
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.repository.ComandaSnapshotRepository
import com.plugpdv.pdv.repository.TableReadRepository
import com.plugpdv.pdv.utils.ComandaSnapshotAuthorityPolicy
import com.plugpdv.pdv.utils.SnapshotAuthorityDecision
import com.plugpdv.pdv.utils.TableManager
import com.plugpdv.pdv.utils.TenantBindingStore
import com.plugpdv.pdv.utils.retryIO
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ReadProvenance {
    INITIAL,
    LOCAL_CACHED,
    REMOTE_REFRESHED
}

@HiltViewModel
class TableOrderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: PosApiService,
    private val catalogDao: CatalogDao,
    private val tableReadRepository: TableReadRepository,
    private val comandaSnapshotRepository: ComandaSnapshotRepository
) : ViewModel() {

    private val gson = Gson()

    private val _table = MutableLiveData<Table?>()
    val table: LiveData<Table?> = _table

    private val _readProvenance = MutableLiveData<ReadProvenance>(ReadProvenance.INITIAL)
    val readProvenance: LiveData<ReadProvenance> = _readProvenance

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _refreshWarning = MutableLiveData<String?>()
    val refreshWarning: LiveData<String?> = _refreshWarning

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var token: String? = null
    private var tableId: String? = null
    private var tableNumber: Int = 0
    private var sectorId: String? = null

    private val pendingAdditions = mutableMapOf<String, Int>()
    private val previousServerQuantities = mutableMapOf<String, Int>()

    fun init(tableId: String?, tableNumber: Int, sectorId: String?, token: String) {
        this.tableId = tableId
        this.tableNumber = tableNumber
        this.sectorId = sectorId
        this.token = token

        pendingAdditions.clear()
        previousServerQuantities.clear()

        viewModelScope.launch {
            loadLocalTableAndSnapshot()
            performSyncTable()
        }
    }

    fun init(table: Table, token: String) {
        this._table.value = table
        init(table.id, table.number, table.sectorId, token)
    }

    private suspend fun loadLocalTableAndSnapshot() {
        val resolvedTable = if (!tableId.isNullOrEmpty()) {
            tableReadRepository.getTableById(tableId!!)
        } else if (tableNumber > 0) {
            tableReadRepository.getTableByNumber(tableNumber, sectorId)
        } else {
            null
        }

        if (resolvedTable == null) return

        _table.value = resolvedTable

        val cId = resolvedTable.comandaId
        if (!cId.isNullOrEmpty()) {
            val tenantId = TenantBindingStore.getActiveTenantId(context)
            if (!tenantId.isNullOrBlank()) {
                val snapshot = comandaSnapshotRepository.getByServerComandaId(tenantId, cId)
                if (snapshot != null) {
                    val decision = ComandaSnapshotAuthorityPolicy.evaluate(snapshot, cId, context)
                    if (decision == SnapshotAuthorityDecision.USABLE || snapshot.syncStatus == "PENDING_MUTATIONS") {
                        applySnapshotToTable(resolvedTable, snapshot)
                        _readProvenance.value = ReadProvenance.LOCAL_CACHED
                        _table.value = resolvedTable
                    }
                }
            }
        }
    }

    private fun applySnapshotToTable(targetTable: Table, snapshot: ComandaSnapshotEntity) {
        try {
            val itemsDto: List<MesaItemDto> = gson.fromJson(snapshot.itemsJson, Array<MesaItemDto>::class.java)?.toList().orEmpty()

            targetTable.items.clear()
            val filteredItems = itemsDto.filter { it.status != "CANCELADO" && it.status != "REMOVIDO" }
            val groupedItems = filteredItems.groupBy { Pair(it.nestedProduct?.id ?: it.produto_id, it.observacao) }

            groupedItems.forEach { (groupKey, dtoList) ->
                val pId = groupKey.first ?: return@forEach
                val obs = groupKey.second
                val firstDto = dtoList.first()
                val serverQty = dtoList.sumOf { it.quantidade ?: 0 }

                val localProduct = runCatching {
                    kotlinx.coroutines.runBlocking { catalogDao.getProductById(pId) }
                }.getOrNull()

                var productName = localProduct?.name
                if (productName.isNullOrEmpty()) {
                    productName = firstDto.nestedProduct?.name ?: firstDto.nome
                }

                // Invariant: Snapshot item price contained in MesaItemDto (preco_unitario / subtotal) is authoritative for the snapshot
                var itemPrice = if (firstDto.preco_unitario != null && firstDto.preco_unitario != 0.0) {
                    firstDto.preco_unitario
                } else if (firstDto.nestedProduct?.selling_price != null && firstDto.nestedProduct?.selling_price != 0.0) {
                    firstDto.nestedProduct?.selling_price
                } else if (firstDto.subtotal != null && firstDto.subtotal != 0.0 && serverQty > 0) {
                    firstDto.subtotal / serverQty
                } else {
                    localProduct?.selling_price
                }

                val product = Product(
                    id = pId,
                    name = productName,
                    selling_price = itemPrice ?: 0.0
                )
                targetTable.items.add(TableItem(product = product, quantity = serverQty).apply {
                    id = firstDto.id
                    serverIds = dtoList.mapNotNull { it.id }.toMutableList()
                    observation = obs
                    paidQuantity = 0
                    isPaid = false
                })
            }
            targetTable.calculateTotal()
        } catch (e: Exception) {
            Log.e("TableOrderViewModel", "Error applying snapshot to table: ${e.message}", e)
        }
    }

    fun syncTable(isNewlyOpened: Boolean = false) {
        viewModelScope.launch {
            performSyncTable(isNewlyOpened)
        }
    }

    private suspend fun performSyncTable(isNewlyOpened: Boolean = false) {
        val currentTable = _table.value ?: return
        val currentToken = token ?: return

        try {
            _isLoading.value = true
            if (isNewlyOpened) {
                kotlinx.coroutines.delay(500)
            }

            val cId = currentTable.comandaId
            if (!cId.isNullOrEmpty()) {
                val detail = retryIO { apiService.getComandaDetail("Bearer $currentToken", cId) }
                val snapshot = comandaSnapshotRepository.cacheRemoteDetail(detail, currentTable)

                if (snapshot != null) {
                    applySnapshotToTable(currentTable, snapshot)
                }
                _readProvenance.value = ReadProvenance.REMOTE_REFRESHED
                _refreshWarning.value = null
                _table.value = currentTable
            } else {
                tableReadRepository.refreshTables(currentToken)
                loadLocalTableAndSnapshot()
            }
        } catch (e: Exception) {
            Log.e("TableOrderViewModel", "Sync failed: ${e.message}", e)
            if (_readProvenance.value == ReadProvenance.LOCAL_CACHED) {
                _refreshWarning.value = "Sem conexão — exibindo dados salvos"
            } else {
                _error.value = "Erro de conexão ao carregar mesa"
            }
        } finally {
            _isLoading.value = false
        }
    }

    fun addItem(product: Product) {
        val currentTable = _table.value ?: return
        val currentToken = token ?: return

        if (_readProvenance.value == ReadProvenance.LOCAL_CACHED && _refreshWarning.value != null) {
            _error.value = "Sem conexão. Os dados salvos podem ser consultados, mas esta ação requer conexão."
            return
        }

        val request = CommandActionRequest().apply {
            action = "add_item"
            mesaId = currentTable.id
            comandaId = currentTable.comandaId
            product_id = product.id
            quantity = 1
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                retryIO { apiService.manageComanda("Bearer $currentToken", request) }
                syncTable()
            } catch (e: java.io.IOException) {
                _error.value = "Sem conexão. Os dados salvos podem ser consultados, mas esta ação requer conexão."
            } catch (e: Exception) {
                _error.value = "Erro ao adicionar item: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun removeItem(item: TableItem, reasonStr: String) {
        val currentTable = _table.value ?: return
        val currentToken = token ?: return

        if (_readProvenance.value == ReadProvenance.LOCAL_CACHED && _refreshWarning.value != null) {
            _error.value = "Sem conexão. Os dados salvos podem ser consultados, mas esta ação requer conexão."
            return
        }

        val request = CommandActionRequest().apply {
            action = "remove_item"
            mesaId = currentTable.id
            comandaId = currentTable.comandaId
            order_id = item.id
            reason = reasonStr
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                retryIO { apiService.manageComanda("Bearer $currentToken", request) }
                syncTable()
            } catch (e: java.io.IOException) {
                _error.value = "Sem conexão. Os dados salvos podem ser consultados, mas esta ação requer conexão."
            } catch (e: Exception) {
                _error.value = "Erro ao remover item: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun enviarCozinha(onSuccess: () -> Unit) {
        val currentTable = _table.value ?: return
        val currentToken = token ?: return

        if (_readProvenance.value == ReadProvenance.LOCAL_CACHED && _refreshWarning.value != null) {
            _error.value = "Sem conexão. Os dados salvos podem ser consultados, mas esta ação requer conexão."
            return
        }

        val request = CommandActionRequest().apply {
            action = "send_kitchen"
            mesaId = currentTable.id
            comandaId = currentTable.comandaId
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                retryIO { apiService.manageComanda("Bearer $currentToken", request) }
                onSuccess()
            } catch (e: java.io.IOException) {
                _error.value = "Sem conexão. Os dados salvos podem ser consultados, mas esta ação requer conexão."
            } catch (e: Exception) {
                _error.value = "Erro ao enviar pedido para a cozinha: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }
}
