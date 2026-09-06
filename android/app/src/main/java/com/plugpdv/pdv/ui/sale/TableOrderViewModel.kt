package com.plugpdv.pdv.ui.sale

import android.content.Context
import android.util.Log
import android.os.SystemClock
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.R
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.CatalogDao
import com.plugpdv.pdv.database.ComandaSnapshotEntity
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.repository.ComandaSnapshotRepository
import com.plugpdv.pdv.repository.TableReadRepository
import com.plugpdv.pdv.utils.ComandaItemHydrator
import com.plugpdv.pdv.utils.ComandaSnapshotAuthorityPolicy
import com.plugpdv.pdv.utils.SnapshotAuthorityDecision
import com.plugpdv.pdv.utils.TableManager
import com.plugpdv.pdv.utils.TenantBindingStore
import com.plugpdv.pdv.utils.retryIO
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import retrofit2.HttpException
import javax.inject.Inject

enum class ReadProvenance {
    INITIAL,
    LOCAL_CACHED,
    REMOTE_REFRESHED
}

data class ComandaAccountingSummary(
    val baseCurrency: String,
    val baseMinorUnitDigits: Int,
    val totalBaseMinor: Long,
    val paidBaseMinor: Long,
    val balanceBaseMinor: Long
)

@HiltViewModel
class TableOrderViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: PosApiService,
    private val catalogDao: CatalogDao,
    private val tableReadRepository: TableReadRepository,
    private val comandaSnapshotRepository: ComandaSnapshotRepository
) : ViewModel() {

    private fun localized(@androidx.annotation.StringRes resource: Int, fallbackCode: String, vararg args: Any): String =
        runCatching { context.getString(resource, *args) }.getOrDefault(fallbackCode)

    private val gson = Gson()

    private val _table = MutableLiveData<Table?>()
    val table: LiveData<Table?> = _table

    private val _readProvenance = MutableLiveData<ReadProvenance>(ReadProvenance.INITIAL)
    val readProvenance: LiveData<ReadProvenance> = _readProvenance

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    /** State for the explicit "Atualizar mesa" action, independent of background refresh. */
    private val _isSubmittingTableUpdate = MutableLiveData(false)
    val isSubmittingTableUpdate: LiveData<Boolean> = _isSubmittingTableUpdate

    private val _isRefreshing = MutableLiveData<Boolean>(false)
    val isRefreshing: LiveData<Boolean> = _isRefreshing

    private val _refreshWarning = MutableLiveData<String?>()
    val refreshWarning: LiveData<String?> = _refreshWarning

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private val _sessionExpired = MutableLiveData<Boolean>()
    val sessionExpired: LiveData<Boolean> = _sessionExpired

    private val _accountingSummary = MutableLiveData<ComandaAccountingSummary?>()
    val accountingSummary: LiveData<ComandaAccountingSummary?> = _accountingSummary

    private var token: String? = null
    private var tableId: String? = null
    private var tableNumber: Int = 0
    private var sectorId: String? = null

    private val pendingAdditions = mutableMapOf<String, Int>()
    private val previousServerQuantities = mutableMapOf<String, Int>()
    private data class QueuedAdd(val product: Product, val request: CommandActionRequest, val sequence: Long)
    private val mutationQueue = ArrayDeque<QueuedAdd>()
    private var mutationWorker: Job? = null
    private var mutationSequence = 0L
    private var reconciliationGeneration = 0L
    private var mutationGeneration = 0L

    /** Payment allocation is a separate authoritative overlay, never a Mesa item source. */
    private suspend fun applyPaymentStateOverlay(targetTable: Table, authToken: String, comandaId: String) {
        val response = retryIO { apiService.getComandaPaymentState("Bearer $authToken", comandaId) }
        val states = response?.itensPaymentState.orEmpty()
        targetTable.items.forEach { item ->
            val state = states.firstOrNull { allocation ->
                val id = allocation.comandaItemId
                id != null && (id == item.id || item.serverIds?.contains(id) == true)
            }
            val ordered = item.quantity.coerceAtLeast(0)
            val paid = state?.paidQuantity
                ?: state?.remainingQuantity?.let { (ordered - it).coerceAtLeast(0) }
                ?: 0
            item.paidQuantity = paid.coerceIn(0, ordered)
            item.isPaid = item.paidQuantity >= ordered && ordered > 0
        }
    }

    fun init(tableId: String?, tableNumber: Int, sectorId: String?, token: String) {
        this.tableId = tableId
        this.tableNumber = tableNumber
        this.sectorId = sectorId
        this.token = token

        pendingAdditions.clear()
        previousServerQuantities.clear()
        mutationQueue.clear()
        mutationWorker?.cancel()
        mutationWorker = null

        viewModelScope.launch {
            loadLocalTableAndSnapshot()
            performSyncTable(showLoading = false)
        }
    }

    fun init(table: Table, token: String) {
        this._table.value = table
        init(table.id, table.number, table.sectorId, token)
    }

    private suspend fun loadLocalTableAndSnapshot() {
        val localStart = SystemClock.elapsedRealtime()
        val resolvedTable = if (!tableId.isNullOrEmpty()) {
            tableReadRepository.getTableById(tableId!!)
        } else if (tableNumber > 0) {
            tableReadRepository.getTableByNumber(tableNumber, sectorId)
        } else {
            null
        }

        if (resolvedTable == null) {
            Log.d("PERF_MESA", "cache_miss_reason=NO_TABLE")
            return
        }
        Log.d("PERF_MESA", "local_mesa_read_ms=${SystemClock.elapsedRealtime() - localStart}")

        _table.value = resolvedTable

        val tenantId = TenantBindingStore.getActiveTenantId(context)
        if (tenantId.isNullOrBlank()) {
            Log.d("PERF_MESA", "cache_miss_reason=TENANT_MISSING")
            return
        }

        var effectiveSnapshot: ComandaSnapshotEntity? = null
        val cId = resolvedTable.comandaId

        // 1. Primary lookup: exact tenantId + serverComandaId
        if (!cId.isNullOrEmpty()) {
            val snapshot = comandaSnapshotRepository.getByServerComandaId(tenantId, cId)
            if (snapshot != null) {
                val decision = ComandaSnapshotAuthorityPolicy.evaluate(snapshot, cId, context)
                if (decision == SnapshotAuthorityDecision.USABLE) {
                    effectiveSnapshot = snapshot
                } else {
                    Log.d("PERF_MESA", "cache_miss_reason=AUTHORITY_REJECTED")
                }
            } else Log.d("PERF_MESA", "cache_miss_reason=NO_SNAPSHOT")
        }

        // 2. Safe fallback: if primary lookup is unavailable and tableId is known, query by exact tenantId + tableId
        val currentTableId = resolvedTable.id
        if (effectiveSnapshot == null && !currentTableId.isNullOrEmpty()) {
            val candidates = comandaSnapshotRepository.getSnapshotsByTableId(tenantId, currentTableId)
            val usableCandidates = candidates.filter { candidate ->
                val isNotClosedOrCancelled = candidate.localStatus !in listOf("CLOSED", "CANCELLED") &&
                        candidate.serverStatus !in listOf("FECHADA", "CANCELADA")
                val isTenantMatch = candidate.tenantId == tenantId
                val isTableMatch = candidate.tableId == currentTableId
                val decision = ComandaSnapshotAuthorityPolicy.evaluate(candidate, candidate.serverComandaId, context)

                isTenantMatch && isTableMatch && isNotClosedOrCancelled && decision == SnapshotAuthorityDecision.USABLE
            }

            if (usableCandidates.size == 1) {
                val candidate = usableCandidates[0]
                // Correction 3: If known comandaId A and candidate B differ, classify as conflict/unknown. Never replace nonblank A with B.
                if (!resolvedTable.comandaId.isNullOrEmpty() && !candidate.serverComandaId.isNullOrEmpty() && resolvedTable.comandaId != candidate.serverComandaId) {
                    Log.w("TableOrderViewModel", "ComandaId mismatch between table (${resolvedTable.comandaId}) and snapshot candidate (${candidate.serverComandaId}) - conflict")
                } else {
                    effectiveSnapshot = candidate
                    // Safely repair local comanda linkage ONLY if missing on table
                    if (resolvedTable.comandaId.isNullOrEmpty() && !candidate.serverComandaId.isNullOrEmpty()) {
                        resolvedTable.comandaId = candidate.serverComandaId
                        tableReadRepository.applyServerConfirmedOpen(
                            tableId = currentTableId,
                            comandaId = candidate.serverComandaId,
                            customerName = resolvedTable.customerName,
                            peopleCount = resolvedTable.people_count,
                            knownNumber = resolvedTable.number,
                            knownSectorId = resolvedTable.sectorId,
                            knownSectorName = resolvedTable.sectorName
                        )
                    }
                }
            } else if (usableCandidates.size > 1) {
                Log.w("TableOrderViewModel", "Multiple usable snapshot candidates (${usableCandidates.size}) found for table $currentTableId - ambiguous/conflict, failing closed")
            }
        }

        if (effectiveSnapshot != null) {
            val productResolutionStart = SystemClock.elapsedRealtime()
            applySnapshotToTable(resolvedTable, effectiveSnapshot)
            Log.d("PERF_MESA", "local_product_resolution_ms=${SystemClock.elapsedRealtime() - productResolutionStart}")
            _readProvenance.value = ReadProvenance.LOCAL_CACHED
            _table.value = resolvedTable
            Log.d("PERF_MESA", "cached_first_frame_ms=${SystemClock.elapsedRealtime() - localStart}")
            Log.d("PERF_MESA", "cached_items_count=${resolvedTable.items.size}")
        } else {
            Log.d("PERF_MESA", "cache_miss_reason=NO_USABLE_SNAPSHOT")
        }
    }

    private suspend fun applySnapshotToTable(targetTable: Table, snapshot: ComandaSnapshotEntity) {
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

                // Snapshot DTO already carries the authoritative historical price
                // and normally the product name. Avoid a blocking DAO lookup for
                // every line during local-first opening.
                var productName = firstDto.nestedProduct?.name ?: firstDto.nome
                if (productName.isNullOrEmpty()) {
                    productName = try { catalogDao.getProductById(pId)?.name } catch (_: Exception) { null }
                }

                // Invariant: Snapshot item price contained in MesaItemDto (preco_unitario / subtotal) is authoritative for the snapshot.
                // Catalog selling_price MUST NOT be used as historical price fallback.
                val itemPrice = ComandaItemHydrator.authoritativeUnitPrice(firstDto, serverQty)

                val product = Product(
                    id = pId,
                    name = productName,
                    selling_price = itemPrice,
                    price_currency = snapshot.baseCurrency
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

            if (snapshot.totalBaseMinor != null && snapshot.paidBaseMinor != null && snapshot.balanceBaseMinor != null && !snapshot.baseCurrency.isNullOrBlank() && snapshot.baseMinorUnitDigits != null) {
                _accountingSummary.value = ComandaAccountingSummary(
                    baseCurrency = snapshot.baseCurrency,
                    baseMinorUnitDigits = snapshot.baseMinorUnitDigits,
                    totalBaseMinor = snapshot.totalBaseMinor,
                    paidBaseMinor = snapshot.paidBaseMinor,
                    balanceBaseMinor = snapshot.balanceBaseMinor
                )
            } else {
                _accountingSummary.value = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("TableOrderViewModel", "Error applying snapshot to table: ${e.message}", e)
        }
    }

    fun syncTable(isNewlyOpened: Boolean = false, showLoading: Boolean = true) {
        viewModelScope.launch {
            performSyncTable(isNewlyOpened, showLoading)
        }
    }

    private suspend fun performSyncTable(isNewlyOpened: Boolean = false, showLoading: Boolean = true) {
        if (mutationQueue.isNotEmpty() || mutationWorker?.isActive == true) {
            Log.d("PERF_MESA", "reconciliation_skipped_stale=true queue_depth=${mutationQueue.size}")
            return
        }
        val currentTable = _table.value ?: return
        val currentToken = token ?: return
        val refreshGeneration = mutationGeneration

        try {
            _isRefreshing.value = true
            if (showLoading) _isLoading.value = true
            val refreshStart = SystemClock.elapsedRealtime()

            val cId = currentTable.comandaId
            if (!cId.isNullOrEmpty()) {
                val detailStart = SystemClock.elapsedRealtime()
                val detail = retryIO { apiService.getComandaDetail("Bearer $currentToken", cId) }
                Log.d("PERF_MESA", "reconciliation_http_ms=${SystemClock.elapsedRealtime() - detailStart}")
                val snapshot = comandaSnapshotRepository.cacheRemoteDetail(detail, currentTable)

                if (snapshot != null) {
                    val decision = ComandaSnapshotAuthorityPolicy.evaluate(snapshot, cId, context)
                    if (decision == SnapshotAuthorityDecision.USABLE) {
                        // Hydrate a detached candidate so the already-published table
                        // cannot briefly expose base items before allocation is known.
                        val candidateTable = currentTable.copy(
                            items = currentTable.items.map { item ->
                                item.copy(
                                    product = item.product.copy(),
                                    serverIds = item.serverIds?.toMutableList()
                                )
                            }.toMutableList()
                        )
                        val previousItemCount = currentTable.items.size
                        val previousAccounting = _accountingSummary.value
                        val snapshotStart = SystemClock.elapsedRealtime()
                        applySnapshotToTable(candidateTable, snapshot)
                        Log.d("PERF_MESA", "snapshot_apply_ms=${SystemClock.elapsedRealtime() - snapshotStart}")
                        try {
                            applyPaymentStateOverlay(candidateTable, currentToken, cId)
                            if (mutationQueue.isEmpty() && mutationGeneration == refreshGeneration) {
                                _table.value = candidateTable
                                Log.d("PERF_MESA", "remote_diff_count=${kotlin.math.abs(candidateTable.items.size - previousItemCount)}")
                            } else {
                                Log.d("PERF_MESA", "reconciliation_skipped_stale=true")
                            }
                        } catch (e: Exception) {
                            Log.w("TableOrderViewModel", "Payment allocation overlay unavailable", e)
                            // Keep the last authoritative combined state intact.
                            _accountingSummary.value = previousAccounting
                        }
                    }
                }
                _readProvenance.value = ReadProvenance.REMOTE_REFRESHED
                _refreshWarning.value = null
            } else {
                tableReadRepository.refreshTables(currentToken)
                loadLocalTableAndSnapshot()
            }
            Log.d("PERF_MESA", "total_authoritative_ms=${SystemClock.elapsedRealtime() - refreshStart}")
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Log.e("TableOrderViewModel", "HTTP error ${e.code()}: ${e.message()}", e)
            when (e.code()) {
                401 -> {
                    _sessionExpired.value = true
                    _error.value = localized(R.string.session_expired, "SESSION_EXPIRED")
                }
                403 -> {
                    val errorBody = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                    _error.value = com.plugpdv.pdv.utils.HttpErrorParser.parse403Message(errorBody, defaultMode = "mesa")
                }
                426 -> {
                    _error.value = localized(R.string.mandatory_app_update, "MANDATORY_APP_UPDATE")
                }
                in 500..599 -> {
                    if (_readProvenance.value == ReadProvenance.LOCAL_CACHED) {
                        _refreshWarning.value = "Sem conexão — exibindo dados salvos"
                    } else {
                        _error.value = localized(R.string.server_error_code, "SERVER_ERROR", e.code())
                    }
                }
                else -> {
                    _error.value = localized(R.string.server_error_code, "SERVER_ERROR", e.code())
                }
            }
        } catch (e: java.io.IOException) {
            Log.e("TableOrderViewModel", "IO error during sync: ${e.message}", e)
            if (_readProvenance.value == ReadProvenance.LOCAL_CACHED) {
                _refreshWarning.value = "Sem conexão — exibindo dados salvos"
            } else {
                _error.value = localized(R.string.load_table_connection_error, "LOAD_TABLE_CONNECTION_ERROR")
            }
        } catch (e: Exception) {
            Log.e("TableOrderViewModel", "Sync failed: ${e.message}", e)
            _error.value = localized(R.string.load_table_error, "LOAD_TABLE_ERROR", e.message.orEmpty())
        } finally {
            _isRefreshing.value = false
            if (showLoading) _isLoading.value = false
        }
    }

    fun addItem(product: Product) {
        val currentTable = _table.value ?: return
        val currentToken = token ?: return
        val cId = currentTable.comandaId

        if (cId.isNullOrEmpty()) {
            _error.value = localized(R.string.comanda_id_not_found, "COMANDA_ID_NOT_FOUND")
            return
        }

        if (_readProvenance.value == ReadProvenance.LOCAL_CACHED && _refreshWarning.value != null) {
            _error.value = localized(R.string.offline_action_requires_connection, "OFFLINE_ACTION_REQUIRES_CONNECTION")
            return
        }

        val request = CommandActionRequest().apply {
            action = "add_item"
            mesaId = currentTable.id
            comandaId = cId
            product_id = product.id
            quantity = 1
            status = "RASCUNHO"
        }

        val tapAt = SystemClock.elapsedRealtime()
        // Immediate, explicitly non-authoritative pending rendering.
        val optimistic = currentTable.copy(items = currentTable.items.map { it.copy(product = it.product.copy(), serverIds = it.serverIds?.toMutableList()) }.toMutableList())
        val existing = optimistic.items.firstOrNull { !it.removed && it.product.id == product.id }
        if (existing != null) existing.quantity += 1 else optimistic.items.add(TableItem(product = product.copy(), quantity = 1, status = "PENDING"))
        _table.value = optimistic
        Log.d("PERF_MESA", "add_visual_ms=${SystemClock.elapsedRealtime() - tapAt}")

        mutationSequence += 1
        mutationGeneration += 1
        mutationQueue.add(QueuedAdd(product, request, mutationSequence))
        Log.d("PERF_MESA", "queue_depth=${mutationQueue.size}")
        startMutationWorker(currentToken)
    }

    private fun startMutationWorker(authToken: String) {
        if (mutationWorker?.isActive == true) return
        mutationWorker = viewModelScope.launch {
            while (mutationQueue.isNotEmpty()) {
                val mutation = mutationQueue.removeFirst()
                val httpStart = SystemClock.elapsedRealtime()
                Log.d("PERF_MESA", "mutation_seq=${mutation.sequence} pending_count=${mutationQueue.size}")
                try {
                    retryIO { apiService.manageComanda("Bearer $authToken", mutation.request) }
                    Log.d("PERF_MESA", "mutation_http_ms=${SystemClock.elapsedRealtime() - httpStart}")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: java.io.IOException) {
                    rollbackOptimisticAddition(mutation.product)
                    _error.value = localized(R.string.offline_action_requires_connection, "OFFLINE_ACTION_REQUIRES_CONNECTION")
                } catch (e: Exception) {
                    rollbackOptimisticAddition(mutation.product)
                    _error.value = localized(R.string.add_item_error, "ADD_ITEM_ERROR", e.localizedMessage.orEmpty())
                }
            }
            reconciliationGeneration += 1
            Log.d("PERF_MESA", "reconciliation_generation=$reconciliationGeneration pending_count=0")
            mutationWorker = null
            syncTable(showLoading = false)
        }
    }

    private fun rollbackOptimisticAddition(product: Product) {
        val current = _table.value ?: return
        val copy = current.copy(items = current.items.map { it.copy(product = it.product.copy(), serverIds = it.serverIds?.toMutableList()) }.toMutableList())
        val item = copy.items.lastOrNull { !it.removed && it.id == null && it.product.id == product.id }
            ?: copy.items.lastOrNull { !it.removed && it.product.id == product.id }
        if (item != null) {
            if (item.quantity > 1) item.quantity -= 1 else copy.items.remove(item)
            _table.value = copy
        }
    }

    fun removeItem(item: TableItem, reasonStr: String) {
        val currentTable = _table.value ?: return
        val currentToken = token ?: return

        if (_readProvenance.value == ReadProvenance.LOCAL_CACHED && _refreshWarning.value != null) {
            _error.value = localized(R.string.offline_action_requires_connection, "OFFLINE_ACTION_REQUIRES_CONNECTION")
            return
        }

        val request = CommandActionRequest().apply {
            action = "cancel_item"
            mesaId = currentTable.id
            comandaId = currentTable.comandaId
            order_id = item.id
            product_id = item.product.id
            reason = reasonStr
            itemIds = item.serverIds
        }

        viewModelScope.launch {
            try {
                _isLoading.value = true
                retryIO { apiService.manageComanda("Bearer $currentToken", request) }
                syncTable()
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                _error.value = localized(R.string.offline_action_requires_connection, "OFFLINE_ACTION_REQUIRES_CONNECTION")
            } catch (e: Exception) {
                _error.value = localized(R.string.remove_item_error, "REMOVE_ITEM_ERROR", e.localizedMessage.orEmpty())
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun enviarCozinha(onSuccess: () -> Unit) {
        // The action is guarded here as well as in the UI so duplicate taps cannot
        // create concurrent submissions for the same table.
        if (_isSubmittingTableUpdate.value == true) return

        val currentTable = _table.value ?: return
        val currentToken = token ?: return
        val cId = currentTable.comandaId

        if (cId.isNullOrEmpty()) {
            _error.value = localized(R.string.comanda_id_not_found, "COMANDA_ID_NOT_FOUND")
            return
        }

        if (_readProvenance.value == ReadProvenance.LOCAL_CACHED && _refreshWarning.value != null) {
            _error.value = localized(R.string.offline_action_requires_connection, "OFFLINE_ACTION_REQUIRES_CONNECTION")
            return
        }

        _isSubmittingTableUpdate.value = true

        val request = CommandActionRequest().apply {
            action = "enviar_cozinha"
            comandaId = cId
        }

        viewModelScope.launch {
            var completed = false
            try {
                _isLoading.value = true
                retryIO { apiService.manageComanda("Bearer $currentToken", request) }
                try {
                    val detail = retryIO { apiService.getComandaDetail("Bearer $currentToken", cId) }
                    comandaSnapshotRepository.cacheRemoteDetail(detail, currentTable)
                } catch (e: Exception) {
                    Log.w("TableOrderViewModel", "Failed to cache snapshot after enviarCozinha: ${e.message}")
                }
                completed = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: java.io.IOException) {
                _error.value = localized(R.string.offline_action_requires_connection, "OFFLINE_ACTION_REQUIRES_CONNECTION")
            } catch (e: Exception) {
                _error.value = localized(R.string.send_kitchen_error, "SEND_KITCHEN_ERROR", e.localizedMessage.orEmpty())
            } finally {
                _isLoading.value = false
                _isSubmittingTableUpdate.value = false
            }
            // Release the action state before navigation so recreation cannot inherit
            // a stale submitting flag.
            if (completed) onSuccess()
        }
    }
}
