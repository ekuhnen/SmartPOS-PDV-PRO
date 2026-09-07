package com.plugpdv.pdv.ui.sale

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.api.PosApiService
import com.google.gson.Gson
import com.plugpdv.pdv.R
import com.plugpdv.pdv.database.ComandaSnapshotEntity
import com.plugpdv.pdv.database.OutboxDao
import com.plugpdv.pdv.database.OutboxOperationEntity
import com.plugpdv.pdv.database.PaymentAttemptDao
import com.plugpdv.pdv.database.TaxEntity
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.outbox.SaleSyncScheduler
import com.plugpdv.pdv.repository.ComandaSnapshotRepository
import com.plugpdv.pdv.repository.TableReadRepository
import com.plugpdv.pdv.repository.TaxRepository
import com.plugpdv.pdv.utils.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject

enum class MoneyAuthorityState {
    LOADING,
    READY_LOCAL,
    READY_REMOTE,
    LOAD_ERROR,
    RECONCILIATION_REQUIRED
}

data class CheckoutUiState(
    val moneyAuthorityState: MoneyAuthorityState = MoneyAuthorityState.LOADING,
    val authoritySource: String? = null,
    val baseCurrency: String? = null,
    val baseMinorUnitDigits: Int? = null,
    val totalBaseMinor: Long? = null,
    val paidBaseMinor: Long? = null,
    val balanceBaseMinor: Long? = null,
    val authoritativeSubtotal: Double? = null,
    val authoritativeTaxAmount: Double? = null,
    val authoritativeTaxSnapshot: TaxSnapshotDto? = null,
    val authoritativeServiceFee: Double? = null,
    val authoritativeServiceFeeMode: String? = null,
    val authoritativeServiceFeePercent: Double? = null,
    val authoritativeComandaVersion: Long? = null,
    val authoritativeTotal: Double? = null,
    val refreshWarning: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val paymentSuccess: Boolean = false,
    val isComandaClosed: Boolean = false,
    val isAwaitingProvider: Boolean = false,
    val isCashProcessing: Boolean = false,
    val itemPaymentStateReady: Boolean = false,
    val itemPaymentStateRefreshing: Boolean = false,
    val itemPaymentStateError: Boolean = false,
    val itemQuote: PaymentQuoteResponse? = null,
    val itemQuoteLoading: Boolean = false,
    val itemQuoteError: Boolean = false,
    val itemQuoteCacheVersion: Long = 0L,
    val isPendingSync: Boolean = false,
    val isPayButtonBlocked: Boolean = true,
    val blockReason: String? = "Carregando dados financeiros...",
    val requiresReconciliation: Boolean = false,
    val currentToPay: Double = 0.0,
    val taxAmount: Double = 0.0,
    val finalToPay: Double = 0.0,
    val splitMode: Int = 0, // 0: Full, 1: People, 2: Items
    val activeTaxes: List<TaxEntity> = emptyList(),
    val fullTableTotalPaid: Double = 0.0,
    val lastPaymentMethod: String? = null,
    val lastPaymentAmount: Double = 0.0,
    val lastPaymentCurrency: String? = null,
    val serviceFeeConfig: ServiceFeeConfig? = null,
    val serviceFeeAmount: Double = 0.0,
    val serviceFeeKind: String? = null,
    val serviceFeeManualValue: Double = 0.0,
    val isServiceFeeSubmitting: Boolean = false,
    val serviceFeeError: String? = null,
    val paymentsHistory: List<ComandaPaymentDto> = emptyList()
)

data class DurableBlockerResult(
    val isBlocked: Boolean = false,
    val isPendingSync: Boolean = false,
    val requiresReconciliation: Boolean = false,
    val reason: String? = null
)

@HiltViewModel
class CheckoutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: PosApiService,
    private val taxRepository: TaxRepository,
    private val outboxDao: OutboxDao,
    private val paymentAttemptDao: PaymentAttemptDao,
    private val outboxSyncManager: OutboxSyncManager,
    private val saleSyncScheduler: SaleSyncScheduler,
    private val comandaSnapshotRepository: ComandaSnapshotRepository,
    private val tableReadRepository: TableReadRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CheckoutUiState())
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    private var table: Table? = null
    private var token: String? = null
    private var sessionId: String? = null
    private var operatorId: String? = null
    private var operatorName: String? = null
    private var selectedComandaId: String? = null

    private fun effectiveComandaId(current: Table? = table): String? = selectedComandaId ?: current?.comandaId

    var comandaBaseCurrency: String? = null
    var moneyAuthorityLoaded: Boolean = false

    // Split by items tracking
    val itemsToPay = mutableListOf<TableItemPayment>()
    private var paymentStateByItemId: List<ComandaItemPaymentStateDto> = emptyList()
    private var itemsPaymentStateLoaded: Boolean = false
    private var itemQuoteRequestVersion: Long = 0L
    private val itemQuoteCache = mutableMapOf<String, PaymentQuoteResponse>()
    private val selectedQuantitiesByItemId = mutableMapOf<String, Int>()
    val itemQuotes: Map<String, PaymentQuoteResponse> get() = itemQuoteCache

    fun currentReceiptAllocations(): List<ComandaPaymentAllocationDto> =
        _uiState.value.paymentsHistory.lastOrNull {
            it.allocationMode.equals("items", ignoreCase = true)
        }?.allocations.orEmpty()

    /** Receipt identity is a backend snapshot; it is never composed from local profile data. */
    suspend fun fetchClosingReceipt(): ComandaReceiptResponse? = withContext(Dispatchers.IO) {
        val cId = effectiveComandaId() ?: return@withContext null
        val auth = token ?: return@withContext null
        runCatching { apiService.getComandaReceipt("Bearer $auth", cId) }
            .onFailure { Log.w("CheckoutViewModel", "Closing receipt identity unavailable", it) }
            .getOrNull()
    }

    /** Items and quantities belonging to the current checkout scope. */
    fun currentChargeItems(): List<Pair<TableItem, Int>> {
        return if (_uiState.value.splitMode == 2) {
            itemsToPay.filter { it.selected && it.selectedQuantity > 0 }
                .map { it.item to it.selectedQuantity }
        } else {
            table?.items.orEmpty()
                .filter { !it.removed && (it.quantity - it.paidQuantity) > 0 }
                .map { it to (it.quantity - it.paidQuantity) }
        }
    }

    fun init(table: Table, token: String, sessionId: String?, opId: String?, opName: String?) {
        this.table = table
        init(table.id, table.number, table.sectorId, token, sessionId, opId, opName)
    }

    fun init(
        tableId: String?,
        tableNumber: Int,
        sectorId: String?,
        token: String,
        sessionId: String?,
        opId: String?,
        opName: String?
        , selectedComandaId: String? = null
    ) {
        this.token = token
        this.sessionId = sessionId
        this.operatorId = opId
        this.operatorName = opName
        this.selectedComandaId = selectedComandaId

        val serviceFeeConfig = ServiceFeeManager.getConfig(context)
        _uiState.value = _uiState.value.copy(
            serviceFeeConfig = serviceFeeConfig,
            serviceFeeKind = if (serviceFeeConfig?.fixedEnabled == true) "fixed" else null,
            moneyAuthorityState = MoneyAuthorityState.LOADING,
            isPayButtonBlocked = true,
            blockReason = "Carregando dados financeiros..."
        )

        viewModelScope.launch {
            // 1. Resolve table from Room if not already set
            var currentTable = this@CheckoutViewModel.table
            if (currentTable == null) {
                if (!selectedComandaId.isNullOrBlank()) {
                    // Standalone comanda: in-memory operational context only; canonical identity is the comanda ID.
                    currentTable = Table(id = null, number = 0, comandaId = selectedComandaId, status = Table.Status.OCCUPIED)
                    this@CheckoutViewModel.table = currentTable
                } else {
                    _uiState.value = _uiState.value.copy(
                        moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                        isPayButtonBlocked = true,
                        blockReason = "Mesa não encontrada no banco de dados.",
                        error = "Mesa não encontrada."
                    )
                    return@launch
                }
            }
            // 2. Evaluate durable blockers from Room
            val durableBlock = checkDurablePaymentBlockers(currentTable)

            // 3. Load & evaluate local snapshot
            val localSnapshot = loadLocalSnapshot(currentTable)
            val cId = effectiveComandaId(currentTable).orEmpty()
            val localAuthorityDecision = if (localSnapshot != null && cId.isNotEmpty()) {
                ComandaSnapshotAuthorityPolicy.evaluate(localSnapshot, cId, context)
            } else {
                SnapshotAuthorityDecision.MISSING_AUTHORITY
            }

            // 4. Apply local authority state deterministically
            applyLocalAuthorityState(localSnapshot, localAuthorityDecision, durableBlock)

            // 5. Perform remote refresh sequentially
            performRemoteRefresh(currentTable, token, cId, durableBlock, localAuthorityDecision)
        }

        // Listen for checkout sync events
        viewModelScope.launch {
            outboxSyncManager.checkoutResultEvents.collect { event ->
                val cId = effectiveComandaId()
                if (cId != null && event.comandaId == cId) {
                    if (event.terminalFailure) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isAwaitingProvider = false,
                            isCashProcessing = false,
                            isPendingSync = false,
                            isPayButtonBlocked = false,
                            paymentSuccess = false,
                            requiresReconciliation = false,
                            blockReason = null,
                            error = context.getString(R.string.cash_error_business, "HTTP 4xx")
                        )
                    } else if (event.requiresReconciliation) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isAwaitingProvider = false,
                            isCashProcessing = false,
                            isPendingSync = false,
                            isPayButtonBlocked = true,
                            paymentSuccess = false,
                            requiresReconciliation = true,
                            blockReason = "Pagamento aprovado requer conciliação"
                        )
                    } else if (event.closed) {
                        fetchComandaPayments {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                isAwaitingProvider = false,
                                isCashProcessing = false,
                                isPendingSync = false,
                                isPayButtonBlocked = false,
                                paymentSuccess = true,
                                isComandaClosed = true,
                                balanceBaseMinor = 0L,
                                currentToPay = 0.0,
                                requiresReconciliation = false,
                                blockReason = null
                            )
                        }
                    } else {
                        fetchComandaPayments {
                            _uiState.value = _uiState.value.copy(
                                isLoading = false,
                                isAwaitingProvider = false,
                                isCashProcessing = false,
                                isPendingSync = false,
                                isPayButtonBlocked = false,
                                paymentSuccess = true,
                                requiresReconciliation = false,
                                blockReason = null
                            )
                        }
                    }
                }
            }
        }

        // Active taxes
        viewModelScope.launch {
            taxRepository.getActiveTaxesLiveData().observeForever { taxes ->
                _uiState.value = _uiState.value.copy(activeTaxes = taxes)
                calculateFinalAmount()
            }
        }
    }

    suspend fun checkDurablePaymentBlockers(currentTable: Table): DurableBlockerResult = withContext(Dispatchers.IO) {
        val cId = effectiveComandaId(currentTable) ?: return@withContext DurableBlockerResult()

        val recentOps = outboxDao.getRecentOperationsForGroup(cId)
            .filter { it.operationType == "COMANDA_CHECKOUT_COMMIT" }

        val waitingOp = recentOps.find { it.status == "WAITING_PAYMENT" }
        val pendingOrProcessingOp = recentOps.find { it.status == "PENDING" || it.status == "PROCESSING" }
        val reconciliationOp = recentOps.find {
            it.status == "REQUIRES_RECONCILIATION" || (it.status == "FAILED" && it.messageKey == "REQUIRES_RECONCILIATION")
        }

        if (reconciliationOp != null) {
            return@withContext DurableBlockerResult(
                isBlocked = true,
                isPendingSync = false,
                requiresReconciliation = true,
                reason = "Pagamento aprovado requer conciliação"
            )
        }

        if (waitingOp != null) {
            val attempt = paymentAttemptDao.getByReference(waitingOp.idempotencyKey)
            return@withContext when (attempt?.status) {
                "PENDING" -> DurableBlockerResult(
                    isBlocked = true,
                    isPendingSync = true,
                    requiresReconciliation = false,
                    reason = "Pagamento aguardando confirmação da maquininha"
                )
                "UNKNOWN" -> DurableBlockerResult(
                    isBlocked = true,
                    isPendingSync = false,
                    requiresReconciliation = true,
                    reason = "Pagamento com status indeterminado"
                )
                "APPROVED" -> DurableBlockerResult(
                    isBlocked = true,
                    isPendingSync = true,
                    requiresReconciliation = false,
                    reason = "Pagamento aprovado aguardando sincronização com o servidor"
                )
                "CANCELLED", "REJECTED", "FAILED_TO_START" -> DurableBlockerResult()
                else -> DurableBlockerResult(
                    isBlocked = true,
                    isPendingSync = false,
                    requiresReconciliation = true,
                    reason = "Pagamento necessita verificação"
                )
            }
        }

        if (pendingOrProcessingOp != null) {
            return@withContext DurableBlockerResult(
                isBlocked = true,
                isPendingSync = true,
                requiresReconciliation = false,
                reason = "Pagamento aprovado aguardando sincronização com o servidor"
            )
        }

        // Case B: Orphaned APPROVED PaymentAttempt
        val approvedAttempts = paymentAttemptDao.getApprovedAttemptsForTableOrOrder(currentTable.number, cId)
        for (att in approvedAttempts) {
            val matchingOp = outboxDao.getById(att.reference)
            if (matchingOp == null) {
                return@withContext DurableBlockerResult(
                    isBlocked = true,
                    isPendingSync = false,
                    requiresReconciliation = true,
                    reason = "Pagamento aprovado na maquininha sem registro de checkout (Requer conciliação)"
                )
            }
        }

        DurableBlockerResult()
    }

    private suspend fun loadLocalSnapshot(currentTable: Table): ComandaSnapshotEntity? = withContext(Dispatchers.IO) {
        val cId = effectiveComandaId(currentTable) ?: return@withContext null
        val tenantId = TenantBindingStore.getActiveTenantId(context) ?: return@withContext null
        comandaSnapshotRepository.getByServerComandaId(tenantId, cId)
    }

    private fun applyLocalAuthorityState(
        snapshot: ComandaSnapshotEntity?,
        decision: SnapshotAuthorityDecision,
        durableBlock: DurableBlockerResult
    ) {
        if (snapshot == null) return

        // Keep the checkout read model coherent with the same authoritative snapshot
        // that supplies the monetary fields. Room's table cache may legitimately have
        // an empty itemsJson after a table refresh.
        table?.items = ComandaItemHydrator.fromSnapshot(snapshot.itemsJson, snapshot.baseCurrency)
        if (_uiState.value.splitMode == 2) {
            if (_uiState.value.itemPaymentStateReady) setupItemsSplit() else itemsToPay.clear()
        }

        val digits = snapshot.baseMinorUnitDigits
        val balDecimal = if (digits != null && snapshot.balanceBaseMinor != null) {
            ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(snapshot.balanceBaseMinor, digits)
        } else {
            null
        }

        when (decision) {
            SnapshotAuthorityDecision.USABLE -> {
                if (digits == null) {
                    moneyAuthorityLoaded = false
                    _uiState.value = _uiState.value.copy(
                        moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                        isPayButtonBlocked = true,
                        blockReason = "Dígitos decimais da moeda não definidos no snapshot.",
                        error = "Dados financeiros inválidos."
                    )
                    return
                }
                moneyAuthorityLoaded = true
                comandaBaseCurrency = snapshot.baseCurrency

                val isBlocked = durableBlock.isBlocked || true // In Stage 03, READY_LOCAL always blocks payment mutation
                val requiresRecon = durableBlock.requiresReconciliation

                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = if (requiresRecon) MoneyAuthorityState.RECONCILIATION_REQUIRED else MoneyAuthorityState.READY_LOCAL,
                    authoritySource = "LOCAL",
                    baseCurrency = snapshot.baseCurrency,
                    baseMinorUnitDigits = digits,
                    totalBaseMinor = snapshot.totalBaseMinor,
                    paidBaseMinor = snapshot.paidBaseMinor,
                    balanceBaseMinor = snapshot.balanceBaseMinor,
                    currentToPay = balDecimal?.toDouble() ?: 0.0,
                    isPendingSync = durableBlock.isPendingSync,
                    isPayButtonBlocked = isBlocked,
                    requiresReconciliation = requiresRecon,
                    blockReason = durableBlock.reason ?: "Sem conexão para processar pagamento"
                )
                calculateFinalAmount()
            }
            SnapshotAuthorityDecision.RECONCILIATION_REQUIRED -> {
                moneyAuthorityLoaded = false
                comandaBaseCurrency = snapshot.baseCurrency
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.RECONCILIATION_REQUIRED,
                    authoritySource = "LOCAL",
                    baseCurrency = snapshot.baseCurrency,
                    baseMinorUnitDigits = digits,
                    totalBaseMinor = snapshot.totalBaseMinor,
                    paidBaseMinor = snapshot.paidBaseMinor,
                    balanceBaseMinor = snapshot.balanceBaseMinor,
                    currentToPay = balDecimal?.toDouble() ?: 0.0,
                    isPendingSync = durableBlock.isPendingSync,
                    requiresReconciliation = true,
                    isPayButtonBlocked = true,
                    blockReason = snapshot.reconciliationReason ?: durableBlock.reason ?: "Comanda requer conciliação com o servidor."
                )
                calculateFinalAmount()
            }
            SnapshotAuthorityDecision.CLOSED -> {
                moneyAuthorityLoaded = false
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.READY_LOCAL,
                    authoritySource = "LOCAL",
                    baseCurrency = snapshot.baseCurrency,
                    baseMinorUnitDigits = digits,
                    totalBaseMinor = snapshot.totalBaseMinor,
                    paidBaseMinor = snapshot.paidBaseMinor,
                    balanceBaseMinor = snapshot.balanceBaseMinor,
                    currentToPay = 0.0,
                    isPayButtonBlocked = true,
                    paymentSuccess = false,
                    isComandaClosed = true,
                    requiresReconciliation = false,
                    blockReason = "Comanda já está fechada."
                )
            }
            SnapshotAuthorityDecision.CANCELLED -> {
                moneyAuthorityLoaded = false
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                    authoritySource = "LOCAL",
                    isPayButtonBlocked = true,
                    requiresReconciliation = false,
                    blockReason = "Comanda cancelada."
                )
            }
            SnapshotAuthorityDecision.WRONG_TENANT -> {
                moneyAuthorityLoaded = false
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                    authoritySource = "LOCAL",
                    isPayButtonBlocked = true,
                    requiresReconciliation = false,
                    blockReason = "Tenant mismatch: dados pertencem a outro estabelecimento."
                )
            }
            SnapshotAuthorityDecision.WRONG_COMANDA -> {
                moneyAuthorityLoaded = false
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                    authoritySource = "LOCAL",
                    isPayButtonBlocked = true,
                    requiresReconciliation = false,
                    blockReason = "Comanda mismatch: snapshot pertence a outra comanda."
                )
            }
            SnapshotAuthorityDecision.CONFLICT -> {
                moneyAuthorityLoaded = false
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.RECONCILIATION_REQUIRED,
                    authoritySource = "LOCAL",
                    isPayButtonBlocked = true,
                    requiresReconciliation = true,
                    blockReason = "Conflito de sincronização detectado na comanda."
                )
            }
            SnapshotAuthorityDecision.MISSING_AUTHORITY -> {
                moneyAuthorityLoaded = false
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                    authoritySource = "LOCAL",
                    isPayButtonBlocked = true,
                    requiresReconciliation = false,
                    blockReason = "Não foi possível carregar os dados financeiros da comanda."
                )
            }
        }
    }

    fun fetchComandaPayments(onRefreshed: (() -> Unit)? = null) {
        val currentTable = table ?: return
        val currentToken = token ?: return
        val cId = effectiveComandaId(currentTable) ?: return
        viewModelScope.launch {
            val durableBlock = checkDurablePaymentBlockers(currentTable)
            val localSnapshot = loadLocalSnapshot(currentTable)
            val localAuthorityDecision = if (localSnapshot != null && cId.isNotEmpty()) {
                ComandaSnapshotAuthorityPolicy.evaluate(localSnapshot, cId, context)
            } else {
                SnapshotAuthorityDecision.MISSING_AUTHORITY
            }
            performRemoteRefresh(currentTable, currentToken, cId, durableBlock, localAuthorityDecision)
            onRefreshed?.invoke()
        }
    }

    fun currentTableForReceipt(): Table? = table

    private suspend fun refreshAuthoritativeItemPaymentState(authToken: String, comandaId: String) {
        val response = retryIO { apiService.getComandaPaymentState("Bearer $authToken", comandaId) }
        paymentStateByItemId = response?.itensPaymentState.orEmpty()
        itemsPaymentStateLoaded = true
    }

    private suspend fun performRemoteRefresh(
        currentTable: Table,
        currentToken: String,
        cId: String,
        durableBlock: DurableBlockerResult,
        localAuthorityDecision: SnapshotAuthorityDecision
    ) {
        try {
            val detail = retryIO { apiService.getComandaDetail("Bearer $currentToken", cId) }
            val currentVersion = _uiState.value.authoritativeComandaVersion
            if (currentVersion != null && detail.versao != null && detail.versao < currentVersion) {
                Log.w("CheckoutViewModel", "Ignoring stale comanda detail revision ${detail.versao}; current=$currentVersion")
                return
            }
            _uiState.value = _uiState.value.copy(
                authoritativeSubtotal = detail.subtotal,
                authoritativeTaxAmount = detail.taxAmount,
                authoritativeTaxSnapshot = TaxSnapshotNormalizer.first(detail.taxSnapshot),
                authoritativeServiceFee = detail.serviceFee,
                authoritativeServiceFeeMode = detail.serviceFeeMode,
                authoritativeServiceFeePercent = detail.serviceFeePercent,
                authoritativeComandaVersion = detail.versao,
                authoritativeTotal = detail.totalLiquido ?: detail.total
            )
            val cachedSnapshot = comandaSnapshotRepository.cacheRemoteDetail(detail, currentTable)

            if (cachedSnapshot == null) {
                // Remote detail could not be normalized into a snapshot
                if (localAuthorityDecision == SnapshotAuthorityDecision.USABLE) {
                    moneyAuthorityLoaded = true
                    _uiState.value = _uiState.value.copy(
                        moneyAuthorityState = MoneyAuthorityState.READY_LOCAL,
                        refreshWarning = "Sem conexão — exibindo dados salvos",
                        isPayButtonBlocked = true,
                        blockReason = durableBlock.reason ?: "Sem conexão para processar pagamento"
                    )
                } else if (localAuthorityDecision == SnapshotAuthorityDecision.RECONCILIATION_REQUIRED || durableBlock.requiresReconciliation) {
                    moneyAuthorityLoaded = false
                    _uiState.value = _uiState.value.copy(
                        moneyAuthorityState = MoneyAuthorityState.RECONCILIATION_REQUIRED,
                        isPayButtonBlocked = true,
                        requiresReconciliation = true,
                        blockReason = durableBlock.reason ?: "Comanda requer conciliação com o servidor.",
                        refreshWarning = "Sem conexão — exibindo dados salvos"
                    )
                } else {
                    moneyAuthorityLoaded = false
                    _uiState.value = _uiState.value.copy(
                        moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                        isPayButtonBlocked = true,
                        requiresReconciliation = false,
                        blockReason = "Não foi possível carregar os dados financeiros da comanda.",
                        error = "Falha ao processar autoridade da comanda."
                    )
                }
                return
            }

            val decision = ComandaSnapshotAuthorityPolicy.evaluate(cachedSnapshot, cId, context)
            // Remote snapshot wins over the potentially empty Room table projection.
            currentTable.items = ComandaItemHydrator.fromSnapshot(cachedSnapshot.itemsJson, cachedSnapshot.baseCurrency)
            try {
                if (_uiState.value.splitMode == 2) {
                    _uiState.value = _uiState.value.copy(itemPaymentStateRefreshing = true)
                }
                refreshAuthoritativeItemPaymentState(currentToken, cId)
                if (_uiState.value.splitMode == 2) setupItemsSplit()
                _uiState.value = _uiState.value.copy(
                    itemPaymentStateReady = true,
                    itemPaymentStateRefreshing = false,
                    itemPaymentStateError = false
                )
            } catch (e: Exception) {
                // Keep a previously authoritative overlay during refresh. On first
                // load, remain unavailable instead of exposing every item as payable.
                val hadReadyOverlay = _uiState.value.itemPaymentStateReady
                itemsPaymentStateLoaded = hadReadyOverlay
                Log.w("CheckoutViewModel", "Item payment allocation refresh unavailable", e)
                _uiState.value = _uiState.value.copy(
                    itemPaymentStateRefreshing = false,
                    itemPaymentStateError = true,
                    itemPaymentStateReady = hadReadyOverlay
                )
            }
            if (_uiState.value.splitMode == 2 && !_uiState.value.itemPaymentStateReady) {
                itemsToPay.clear()
            }
            val digits = cachedSnapshot.baseMinorUnitDigits
            val baseCurrency = cachedSnapshot.baseCurrency
            val totalBaseMinor = cachedSnapshot.totalBaseMinor
            val paidBaseMinor = cachedSnapshot.paidBaseMinor
            val balanceBaseMinor = cachedSnapshot.balanceBaseMinor

            val balDecimal = if (digits != null && balanceBaseMinor != null) {
                ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(balanceBaseMinor, digits)
            } else {
                null
            }

            val isComandaClosed = detail.status.equals("FECHADA", ignoreCase = true) || decision == SnapshotAuthorityDecision.CLOSED
            val requiresRecon = durableBlock.requiresReconciliation || decision == SnapshotAuthorityDecision.RECONCILIATION_REQUIRED || detail.requiresReconciliation || baseCurrency.isNullOrBlank() || digits == null

            if (requiresRecon) {
                moneyAuthorityLoaded = false
                comandaBaseCurrency = baseCurrency
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.RECONCILIATION_REQUIRED,
                    authoritySource = "REMOTE",
                    baseCurrency = baseCurrency,
                    baseMinorUnitDigits = digits,
                    totalBaseMinor = totalBaseMinor,
                    paidBaseMinor = paidBaseMinor,
                    balanceBaseMinor = balanceBaseMinor,
                    paymentsHistory = detail.pagamentos.orEmpty(),
                    currentToPay = balDecimal?.toDouble() ?: 0.0,
                    isPendingSync = durableBlock.isPendingSync,
                    isPayButtonBlocked = true,
                    requiresReconciliation = true,
                    paymentSuccess = false,
                    isComandaClosed = false,
                    blockReason = cachedSnapshot.reconciliationReason ?: durableBlock.reason ?: if (baseCurrency.isNullOrBlank()) "Moeda-base não definida no servidor" else if (digits == null) "Dígitos decimais da moeda não definidos" else "Comanda requer conciliação com o servidor.",
                    refreshWarning = null
                )
            } else if (isComandaClosed) {
                moneyAuthorityLoaded = true
                comandaBaseCurrency = baseCurrency
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.READY_REMOTE,
                    authoritySource = "REMOTE",
                    baseCurrency = baseCurrency,
                    baseMinorUnitDigits = digits,
                    totalBaseMinor = totalBaseMinor,
                    paidBaseMinor = paidBaseMinor,
                    balanceBaseMinor = balanceBaseMinor,
                    paymentsHistory = detail.pagamentos.orEmpty(),
                    currentToPay = 0.0,
                    isPendingSync = false,
                    isPayButtonBlocked = true,
                    paymentSuccess = false,
                    isComandaClosed = true,
                    requiresReconciliation = false,
                    blockReason = "Comanda já está fechada.",
                    refreshWarning = null
                )
            } else if (decision == SnapshotAuthorityDecision.USABLE) {
                if (durableBlock.isBlocked) {
                    if (durableBlock.requiresReconciliation) {
                        moneyAuthorityLoaded = false
                        _uiState.value = _uiState.value.copy(
                            moneyAuthorityState = MoneyAuthorityState.RECONCILIATION_REQUIRED,
                            authoritySource = "REMOTE",
                            baseCurrency = baseCurrency,
                            baseMinorUnitDigits = digits,
                            totalBaseMinor = totalBaseMinor,
                            paidBaseMinor = paidBaseMinor,
                            balanceBaseMinor = balanceBaseMinor,
                            paymentsHistory = detail.pagamentos.orEmpty(),
                            currentToPay = balDecimal?.toDouble() ?: 0.0,
                            isPendingSync = durableBlock.isPendingSync,
                            isPayButtonBlocked = true,
                            requiresReconciliation = true,
                            paymentSuccess = false,
                            isComandaClosed = false,
                            blockReason = durableBlock.reason ?: "Pagamento aprovado requer conciliação",
                            refreshWarning = null
                        )
                    } else {
                        moneyAuthorityLoaded = true
                        comandaBaseCurrency = baseCurrency
                        _uiState.value = _uiState.value.copy(
                            moneyAuthorityState = MoneyAuthorityState.READY_LOCAL,
                            authoritySource = "LOCAL",
                            baseCurrency = baseCurrency,
                            baseMinorUnitDigits = digits,
                            totalBaseMinor = totalBaseMinor,
                            paidBaseMinor = paidBaseMinor,
                            balanceBaseMinor = balanceBaseMinor,
                            paymentsHistory = detail.pagamentos.orEmpty(),
                            currentToPay = balDecimal?.toDouble() ?: 0.0,
                            isPendingSync = durableBlock.isPendingSync,
                            isPayButtonBlocked = true,
                            requiresReconciliation = false,
                            paymentSuccess = false,
                            isComandaClosed = false,
                            blockReason = durableBlock.reason ?: "Pagamento aguardando sincronização",
                            refreshWarning = null
                        )
                    }
                } else {
                    moneyAuthorityLoaded = true
                    comandaBaseCurrency = baseCurrency
                    _uiState.value = _uiState.value.copy(
                        moneyAuthorityState = MoneyAuthorityState.READY_REMOTE,
                        authoritySource = "REMOTE",
                        baseCurrency = baseCurrency,
                        baseMinorUnitDigits = digits,
                        totalBaseMinor = totalBaseMinor,
                        paidBaseMinor = paidBaseMinor,
                        balanceBaseMinor = balanceBaseMinor,
                        paymentsHistory = detail.pagamentos.orEmpty(),
                        currentToPay = balDecimal?.toDouble() ?: 0.0,
                        isPendingSync = durableBlock.isPendingSync,
                        isPayButtonBlocked = false,
                        requiresReconciliation = false,
                        paymentSuccess = false,
                        isComandaClosed = false,
                        blockReason = null,
                        refreshWarning = null
                    )
                }
            } else {
                moneyAuthorityLoaded = false
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                    authoritySource = "REMOTE",
                    isPayButtonBlocked = true,
                    requiresReconciliation = false,
                    paymentSuccess = false,
                    isComandaClosed = false,
                    blockReason = "Snapshot de comanda inválido (${decision.name}).",
                    error = "Dados da comanda inválidos."
                )
            }
            calculateFinalAmount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Log.e("CheckoutViewModel", "HTTP error ${e.code()} fetching comanda detail: ${e.message}", e)
            if (e.code() == 401 || e.code() == 403 || e.code() == 426) {
                moneyAuthorityLoaded = false
                val reason = when (e.code()) {
                    426 -> "Atualização obrigatória do aplicativo necessária."
                    401 -> "Sessão expirada. Faça login novamente."
                    403 -> {
                        val errorBody = try { e.response()?.errorBody()?.string() } catch (_: Exception) { null }
                        com.plugpdv.pdv.utils.HttpErrorParser.parse403Message(errorBody, defaultMode = "comanda")
                    }
                    else -> "Erro no servidor (Código: ${e.code()})"
                }
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                    isPayButtonBlocked = true,
                    blockReason = reason,
                    error = reason
                )
            } else if (e.code() in 500..599) {
                if (localAuthorityDecision == SnapshotAuthorityDecision.USABLE) {
                    moneyAuthorityLoaded = true
                    _uiState.value = _uiState.value.copy(
                        moneyAuthorityState = MoneyAuthorityState.READY_LOCAL,
                        refreshWarning = "Sem conexão — exibindo dados salvos",
                        isPayButtonBlocked = true,
                        blockReason = durableBlock.reason ?: "Servidor indisponível — exibindo dados salvos"
                    )
                } else if (localAuthorityDecision == SnapshotAuthorityDecision.RECONCILIATION_REQUIRED || durableBlock.requiresReconciliation) {
                    moneyAuthorityLoaded = false
                    _uiState.value = _uiState.value.copy(
                        moneyAuthorityState = MoneyAuthorityState.RECONCILIATION_REQUIRED,
                        isPayButtonBlocked = true,
                        requiresReconciliation = true,
                        blockReason = durableBlock.reason ?: "Comanda requer conciliação com o servidor.",
                        refreshWarning = "Sem conexão — exibindo dados salvos"
                    )
                } else {
                    moneyAuthorityLoaded = false
                    _uiState.value = _uiState.value.copy(
                        moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                        isPayButtonBlocked = true,
                        requiresReconciliation = false,
                        blockReason = "Servidor indisponível (Código: ${e.code()}).",
                        error = "Servidor indisponível (Código: ${e.code()})."
                    )
                }
            } else {
                moneyAuthorityLoaded = false
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                    isPayButtonBlocked = true,
                    blockReason = "Erro ao carregar dados financeiros (Código: ${e.code()})",
                    error = "Erro no servidor (Código: ${e.code()})"
                )
            }
        } catch (e: Exception) {
            Log.e("CheckoutViewModel", "Network error fetching comanda payments: ${e.message}", e)
            if (localAuthorityDecision == SnapshotAuthorityDecision.USABLE) {
                // Keep READY_LOCAL, do not downgrade to LOAD_ERROR
                moneyAuthorityLoaded = true
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.READY_LOCAL,
                    refreshWarning = "Sem conexão — exibindo dados salvos",
                    isPayButtonBlocked = true,
                    blockReason = durableBlock.reason ?: "Sem conexão para processar pagamento"
                )
            } else if (localAuthorityDecision == SnapshotAuthorityDecision.RECONCILIATION_REQUIRED || durableBlock.requiresReconciliation) {
                // Keep RECONCILIATION_REQUIRED, network failure must never erase reconciliation requirement
                moneyAuthorityLoaded = false
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.RECONCILIATION_REQUIRED,
                    isPayButtonBlocked = true,
                    requiresReconciliation = true,
                    blockReason = durableBlock.reason ?: "Comanda requer conciliação com o servidor.",
                    refreshWarning = "Sem conexão — exibindo dados salvos"
                )
            } else {
                moneyAuthorityLoaded = false
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                    isPayButtonBlocked = true,
                    requiresReconciliation = false,
                    blockReason = "Não foi possível carregar os dados financeiros da comanda.",
                    error = "Erro de conexão ao carregar pagamentos da comanda."
                )
            }
        }
    }

    fun setSplitMode(mode: Int) {
        _uiState.value = _uiState.value.copy(splitMode = mode)
        val digits = _uiState.value.baseMinorUnitDigits
        val balDecimal = if (digits != null) {
            _uiState.value.balanceBaseMinor?.let {
                ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits)
            } ?: BigDecimal.ZERO
        } else {
            BigDecimal.ZERO
        }

        when (mode) {
            0 -> _uiState.value = _uiState.value.copy(currentToPay = balDecimal.toDouble())
            1 -> updatePeopleSplit(1) // Default 1 person
            2 -> {
                if (_uiState.value.itemPaymentStateReady) setupItemsSplit() else itemsToPay.clear()
                val hasUnknownHistoricalPrice = table?.items.orEmpty().any { !it.removed && it.product.selling_price == null }
                _uiState.value = _uiState.value.copy(
                    itemPaymentStateRefreshing = !_uiState.value.itemPaymentStateReady,
                    itemPaymentStateError = false,
                    isPayButtonBlocked = true,
                    blockReason = if (hasUnknownHistoricalPrice) "Divis\u00e3o por itens indispon\u00edvel: item com pre\u00e7o hist\u00f3rico desconhecido." else _uiState.value.blockReason
                )
                loadItemsPaymentState()
            }
        }
        calculateFinalAmount()
    }

    fun updatePeopleSplit(count: Int) {
        if (count > 0) {
            val digits = _uiState.value.baseMinorUnitDigits
            val totalDecimal = if (digits != null) {
                _uiState.value.totalBaseMinor?.let {
                    ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits)
                } ?: BigDecimal.ZERO
            } else {
                BigDecimal.ZERO
            }
            _uiState.value = _uiState.value.copy(currentToPay = totalDecimal.toDouble() / count)
        } else {
            _uiState.value = _uiState.value.copy(currentToPay = 0.0)
        }
        calculateFinalAmount()
    }

    private fun setupItemsSplit() {
        itemsToPay.clear()
        val baseItems = table?.items.orEmpty().filter { !it.removed }
        val overlaidItems = if (itemsPaymentStateLoaded) {
            ComandaPaymentStateAdapter.apply(baseItems, paymentStateByItemId)
        } else baseItems.map { it.copy(paidQuantity = 0, isPaid = false) }
        val activeItems = overlaidItems.filter { it.quantity > it.paidQuantity }
        activeItems.forEach {
            val payment = TableItemPayment(it)
            payment.selectedQuantity = selectedQuantitiesByItemId[it.id].orZero().coerceAtMost(it.quantity - it.paidQuantity)
            payment.selected = payment.selectedQuantity > 0
            itemsToPay.add(payment)
        }
        val hasUnknownPrice = activeItems.any { it.product.selling_price == null }
        if (hasUnknownPrice) {
            _uiState.value = _uiState.value.copy(
                currentToPay = 0.0,
                isPayButtonBlocked = true,
                blockReason = "Divisão por itens indisponível: item com preço histórico desconhecido."
            )
        } else {
            _uiState.value = _uiState.value.copy(currentToPay = 0.0)
        }
        prequotePayableItems(activeItems)
    }

    private fun Int?.orZero(): Int = this ?: 0

    private fun quoteKey(itemId: String, quantity: Int): String = "$itemId|$quantity|${CurrencyManager.getInstance().selectedCurrency}"

    private fun prequotePayableItems(items: List<TableItem>) {
        val currentTable = table ?: return
        val authToken = token ?: return
        val cId = effectiveComandaId(currentTable) ?: return
        items.filter { it.id != null && it.quantity > it.paidQuantity }.forEach { item ->
            val id = requireNotNull(item.id)
            if (itemQuoteCache.containsKey(quoteKey(id, 1))) return@forEach
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    apiService.quoteComandaItems(
                        "Bearer $authToken",
                        PaymentQuoteRequest(comandaId = cId, items = listOf(ComandaItemAllocation(id, 1)), forma = "DINHEIRO", moeda = CurrencyManager.getInstance().selectedCurrency)
                    )
                }.onSuccess { quote ->
                    itemQuoteCache[quoteKey(id, 1)] = quote
                    _uiState.value = _uiState.value.copy(itemQuoteCacheVersion = _uiState.value.itemQuoteCacheVersion + 1)
                }
            }
        }
    }

    private fun loadItemsPaymentState() {
        val currentTable = table ?: return
        val currentToken = token ?: return
        val cId = effectiveComandaId(currentTable) ?: return
        val hadReadyOverlay = _uiState.value.itemPaymentStateReady
        itemsPaymentStateLoaded = hadReadyOverlay
        _uiState.value = _uiState.value.copy(itemPaymentStateRefreshing = true, itemPaymentStateError = false)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = retryIO { apiService.getComandaPaymentState("Bearer $currentToken", cId) }
                paymentStateByItemId = response?.itensPaymentState.orEmpty()
                itemQuoteCache.clear()
                itemsPaymentStateLoaded = true
                withContext(Dispatchers.Main) {
                    setupItemsSplit()
                    val hasUnknownPrice = itemsToPay.any { it.item.product.selling_price == null }
                    _uiState.value = _uiState.value.copy(
                        error = null,
                        itemPaymentStateReady = true,
                        itemPaymentStateRefreshing = false,
                        itemPaymentStateError = false,
                        isPayButtonBlocked = itemsToPay.isEmpty() || hasUnknownPrice
                    )
                }
            } catch (_: Exception) {
                itemsPaymentStateLoaded = hadReadyOverlay
                withContext(Dispatchers.Main) {
                    if (!hadReadyOverlay) itemsToPay.clear()
                    _uiState.value = _uiState.value.copy(
                        itemPaymentStateReady = hadReadyOverlay,
                        itemPaymentStateRefreshing = false,
                        itemPaymentStateError = true,
                        isPayButtonBlocked = if (hadReadyOverlay) _uiState.value.isPayButtonBlocked else true,
                        blockReason = "No fue posible consultar los ítems pendientes."
                    )
                }
            }
        }
    }

    fun onItemSelected(position: Int, isSelected: Boolean) {
        if (position in 0 until itemsToPay.size) {
            val item = itemsToPay[position]
            item.selected = isSelected
            if (isSelected && item.selectedQuantity <= 0) {
                item.selectedQuantity = item.item.quantity - item.item.paidQuantity
            }
            if (!isSelected) item.selectedQuantity = 0
            item.item.id?.let { selectedQuantitiesByItemId[it] = item.selectedQuantity }
            calculateItemsTotal()
        }
    }

    fun updateItemSelectedQuantity(position: Int, delta: Int) {
        if (position !in 0 until itemsToPay.size) return
        val payment = itemsToPay[position]
        val remaining = (payment.item.quantity - payment.item.paidQuantity).coerceAtLeast(0)
        payment.selectedQuantity = (payment.selectedQuantity + delta).coerceIn(0, remaining)
        payment.selected = payment.selectedQuantity > 0
        payment.item.id?.let { selectedQuantitiesByItemId[it] = payment.selectedQuantity }
        calculateItemsTotal()
    }

    private fun calculateItemsTotal() {
        val selectedItems = itemsToPay.filter { it.selected }
        val allocations = selectedItems.filter { it.selectedQuantity > 0 }.mapNotNull {
            it.item.id?.let { id -> ComandaItemAllocation(id, it.selectedQuantity) }
        }
        itemQuoteRequestVersion += 1
        val requestVersion = itemQuoteRequestVersion
        val cachedSingleQuote = if (allocations.size == 1) {
            itemQuoteCache[quoteKey(allocations[0].comandaItemId, allocations[0].quantity)]
        } else null
        _uiState.value = _uiState.value.copy(
            currentToPay = 0.0,
            finalToPay = 0.0,
            itemQuote = cachedSingleQuote,
            itemQuoteLoading = allocations.isNotEmpty() && cachedSingleQuote == null,
            itemQuoteError = false,
            isPayButtonBlocked = allocations.isNotEmpty() && cachedSingleQuote == null,
            blockReason = if (allocations.isEmpty()) null else if (cachedSingleQuote == null) "Atualizando valor..." else null
        )
        if (cachedSingleQuote != null) applyItemsQuote(cachedSingleQuote)
        else if (allocations.isNotEmpty()) requestItemsPaymentQuote(allocations, "DINHEIRO", requestVersion)
        /* quote authority is resolved asynchronously above */
        /*
        val hasUnknownPrice = selectedItems.any { it.item.product.selling_price == null }
        if (hasUnknownPrice) {
            _uiState.value = _uiState.value.copy(
                currentToPay = 0.0,
                isPayButtonBlocked = true,
                blockReason = "Divisão por itens indisponível: item selecionado com preço histórico desconhecido."
            )
        } else {
            var total = 0.0
            selectedItems.forEach {
                total += (it.item.product.selling_price ?: 0.0) * it.selectedQuantity
            }
            _uiState.value = _uiState.value.copy(currentToPay = total)
        }
        calculateFinalAmount()*/
    }

    private fun requestItemsPaymentQuote(
        allocations: List<ComandaItemAllocation>,
        forma: String,
        requestVersion: Long
    ) {
        val currentTable = table ?: return
        val authToken = token ?: return
        val comandaId = effectiveComandaId(currentTable) ?: return
        val currency = CurrencyManager.getInstance().selectedCurrency
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val quote = retryIO {
                    apiService.quoteComandaItems(
                        "Bearer $authToken",
                        PaymentQuoteRequest(comandaId = comandaId, items = allocations, forma = forma, moeda = currency)
                    )
                }
                withContext(Dispatchers.Main) {
                    if (requestVersion == itemQuoteRequestVersion) applyItemsQuote(quote)
                }
            } catch (e: Exception) {
                Log.w("CheckoutViewModel", "Payment quote failed", e)
                withContext(Dispatchers.Main) {
                    if (requestVersion == itemQuoteRequestVersion) {
                        _uiState.value = _uiState.value.copy(
                            itemQuote = null, itemQuoteLoading = false, itemQuoteError = true,
                            currentToPay = 0.0, finalToPay = 0.0, isPayButtonBlocked = true,
                            blockReason = "Não foi possível calcular o valor dos itens."
                        )
                    }
                }
            }
        }
    }

    private fun applyItemsQuote(quote: PaymentQuoteResponse) {
        val accounting = quote.accountingTotal ?: throw IllegalStateException("PAYMENT_QUOTE_AMOUNT_MISSING")
        _uiState.value = _uiState.value.copy(
            itemQuote = quote,
            itemQuoteLoading = false,
            itemQuoteError = false,
            currentToPay = accounting,
            taxAmount = quote.allocatedTax ?: 0.0,
            serviceFeeAmount = quote.allocatedService ?: 0.0,
            finalToPay = accounting,
            isPayButtonBlocked = false,
            blockReason = null
        )
    }

    suspend fun refreshItemsQuoteForPayment(method: PaymentMethod): PaymentQuoteResponse {
        val currentTable = table ?: throw IllegalStateException("COMANDA_NOT_LOADED")
        val authToken = token ?: throw IllegalStateException("AUTH_REQUIRED")
        val allocations = itemsToPay.filter { it.selected && it.selectedQuantity > 0 }.map {
            ComandaItemAllocation(requireNotNull(it.item.id) { "ITEM_SPLIT_ID_MISSING" }, it.selectedQuantity)
        }
        if (allocations.isEmpty()) throw IllegalStateException("ITEM_SPLIT_SELECTION_REQUIRED")
        val requestVersion = ++itemQuoteRequestVersion
        val quote = retryIO {
            apiService.quoteComandaItems(
                "Bearer $authToken",
                PaymentQuoteRequest(
                    comandaId = requireNotNull(effectiveComandaId(currentTable)),
                    items = allocations,
                    forma = method.apiValue,
                    moeda = CurrencyManager.getInstance().selectedCurrency
                )
            )
        }
        withContext(Dispatchers.Main) {
            if (requestVersion != itemQuoteRequestVersion) {
                throw IllegalStateException("PAYMENT_QUOTE_STALE")
            }
            applyItemsQuote(quote)
        }
        return quote
    }

    private fun calculateFinalAmount() {
        val state = _uiState.value
        if (state.splitMode == 2) {
            val quote = state.itemQuote
            _uiState.value = state.copy(
                taxAmount = quote?.allocatedTax ?: 0.0,
                serviceFeeAmount = quote?.allocatedService ?: 0.0,
                finalToPay = quote?.accountingTotal ?: 0.0
            )
            return
        }
        val baseToPay = state.currentToPay.coerceAtLeast(0.0)
        // Comanda money is server-authoritative. Payment scope never accrues tax/fee again.
        val tax = state.authoritativeTaxAmount ?: 0.0
        val sfAmount = state.authoritativeServiceFee ?: 0.0

        _uiState.value = _uiState.value.copy(
            taxAmount = tax,
            serviceFeeAmount = sfAmount,
            finalToPay = baseToPay
        )
    }

    fun refreshCalculations() {
        calculateFinalAmount()
    }

    fun overrideServiceFee(kind: String, value: Double = 0.0, onFinished: ((Boolean) -> Unit)? = null) {
        val currentTable = table
        val comandaId = effectiveComandaId(currentTable)
        if (!comandaId.isNullOrBlank() && token != null) {
            setAuthoritativeServiceFee(comandaId, kind, value, onFinished)
            return
        }
        _uiState.value = _uiState.value.copy(
            serviceFeeKind = kind,
            serviceFeeManualValue = value
        )
        calculateFinalAmount()
        onFinished?.invoke(true)
    }

    private fun serviceFeeMessage(code: String?): String = when (code) {
        "SERVICE_FEE_MODE_INVALID" -> context.getString(R.string.service_fee_mode_invalid)
        "SERVICE_FEE_PERCENT_INVALID" -> context.getString(R.string.service_fee_percent_invalid)
        "SERVICE_FEE_AMOUNT_INVALID" -> context.getString(R.string.service_fee_amount_invalid)
        "SERVICE_FEE_CURRENCY_MISMATCH", "COMANDA_CURRENCY_MISSING" -> context.getString(R.string.service_fee_currency_mismatch)
        "COMANDA_NOT_EDITABLE", "COMANDA_NOT_FOUND", "TENANT_MISMATCH" -> context.getString(R.string.comanda_not_editable)
        "STALE_COMANDA_REVISION" -> context.getString(R.string.stale_comanda_revision)
        "SERVICE_FEE_LOCKED_BY_ALLOCATION" -> context.getString(R.string.service_fee_locked_by_allocation)
        "SERVICE_FEE_BELOW_SETTLED" -> context.getString(R.string.service_fee_below_settled)
        else -> context.getString(R.string.service_fee_update_error)
    }

    private suspend fun responseErrorCode(response: retrofit2.Response<*>): String? {
        return runCatching {
            val body = response.errorBody()?.string() ?: return@runCatching null
            Gson().fromJson(body, Map::class.java)["code"]?.toString()
                ?: Gson().fromJson(body, Map::class.java)["message_key"]?.toString()
        }.getOrNull()
    }

    private fun setAuthoritativeServiceFee(
        comandaId: String,
        kind: String,
        value: Double,
        onFinished: ((Boolean) -> Unit)? = null
    ) {
        if (_uiState.value.isServiceFeeSubmitting) return
        val authToken = token ?: return
        val currency = (_uiState.value.baseCurrency ?: comandaBaseCurrency)?.uppercase()
        val expectedVersion = _uiState.value.authoritativeComandaVersion
        if (currency.isNullOrBlank() || expectedVersion == null) {
            _uiState.value = _uiState.value.copy(serviceFeeError = context.getString(R.string.comanda_financial_data_not_loaded))
            onFinished?.invoke(false)
            return
        }

        val mode = if (kind == "manual_value") "amount" else "percentage"
        val request = CommandActionRequest(
            action = "set_service_fee",
            comandaId = comandaId,
            mode = mode,
            comandaVersion = expectedVersion
        ).apply {
            when (kind) {
                "manual_percent" -> {
                    percentage = BigDecimal.valueOf(value)
                }
                "manual_value" -> {
                    serviceFeeAmount = BigDecimal(MoneyDecimal.toProtocolAmount(BigDecimal.valueOf(value), currency))
                    this.serviceFeeCurrency = currency
                }
                "waived" -> {
                    percentage = BigDecimal.ZERO
                }
                else -> {
                    // The existing "fixed/default" option is the configured
                    // restaurant percentage; the server remains the authority.
                    percentage = BigDecimal.valueOf(_uiState.value.serviceFeeConfig?.fixedPercent ?: 0.0)
                }
            }
        }
        val key = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val payload = Gson().toJson(request)
        _uiState.value = _uiState.value.copy(isServiceFeeSubmitting = true, serviceFeeError = null)

        viewModelScope.launch(Dispatchers.IO) {
            var success = false
            try {
                // Persist the exact logical mutation before the first network attempt.
                outboxDao.insert(OutboxOperationEntity(
                    id = key,
                    operationType = "COMANDA_SERVICE_FEE",
                    targetGroupKey = comandaId,
                    payloadJson = payload,
                    createdAt = now,
                    idempotencyKey = key,
                    status = "PROCESSING"
                ))
                val response = retryIO { apiService.setComandaServiceFee("Bearer $authToken", key, request) }
                if (!response.isSuccessful || response.body()?.ok != true) {
                    val code = responseErrorCode(response) ?: "SERVICE_FEE_UPDATE_FAILED"
                    outboxDao.markAsFailedWithKey(key, code, code, false)
                    if (code == "STALE_COMANDA_REVISION") {
                        withContext(Dispatchers.Main) { fetchComandaPayments() }
                    }
                    throw IllegalStateException(code)
                }
                val authority = requireNotNull(response.body())
                comandaSnapshotRepository.applyServiceFeeAuthority(comandaId, authority)
                outboxDao.markAsSynced(key)
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        authoritativeServiceFee = authority.serviceFee,
                        authoritativeServiceFeeMode = authority.serviceFeeMode,
                        authoritativeServiceFeePercent = authority.serviceFeePercent,
                        authoritativeSubtotal = authority.subtotal,
                        authoritativeTaxAmount = authority.taxAmount,
                        authoritativeTaxSnapshot = TaxSnapshotNormalizer.first(authority.taxSnapshot),
                        authoritativeTotal = authority.totalLiquido,
                        totalBaseMinor = authority.totalLiquido?.let { MoneyDecimal.toMinorUnits(BigDecimal.valueOf(it), currency) },
                        paidBaseMinor = authority.totalPagoBase?.let { MoneyDecimal.toMinorUnits(BigDecimal.valueOf(it), currency) },
                        balanceBaseMinor = authority.saldoBase?.let { MoneyDecimal.toMinorUnits(BigDecimal.valueOf(it), currency) },
                        currentToPay = authority.saldoBase ?: _uiState.value.currentToPay,
                        finalToPay = authority.saldoBase ?: _uiState.value.finalToPay,
                        authoritativeComandaVersion = authority.versao,
                        serviceFeeAmount = authority.serviceFee,
                        serviceFeeKind = authority.serviceFeeMode,
                        serviceFeeManualValue = authority.serviceFeePercent ?: authority.serviceFee,
                        isServiceFeeSubmitting = false,
                        serviceFeeError = null
                    )
                    // A service-fee mutation changes the allocation basis of
                    // every ITEMS quote.  Keep the operator's selection, but
                    // discard quotes from the previous comanda revision and
                    // obtain a fresh authoritative quote before payment is
                    // enabled again.
                    invalidateItemsQuoteAfterAuthorityChange()
                }
                success = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val code = e.message
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(
                        isServiceFeeSubmitting = false,
                        serviceFeeError = serviceFeeMessage(code)
                    )
                }
            } finally {
                if (!success) withContext(Dispatchers.Main) { onFinished?.invoke(false) }
                else withContext(Dispatchers.Main) { onFinished?.invoke(true) }
            }
        }
    }

    private fun invalidateItemsQuoteAfterAuthorityChange() {
        itemQuoteRequestVersion += 1
        itemQuoteCache.clear()
        if (_uiState.value.splitMode != 2) return

        val allocations = itemsToPay.filter { it.selected && it.selectedQuantity > 0 }
            .mapNotNull { payment ->
                payment.item.id?.let { id -> ComandaItemAllocation(id, payment.selectedQuantity) }
            }
        val requestVersion = itemQuoteRequestVersion
        _uiState.value = _uiState.value.copy(
            itemQuote = null,
            itemQuoteLoading = allocations.isNotEmpty(),
            itemQuoteError = false,
            currentToPay = 0.0,
            finalToPay = 0.0,
            isPayButtonBlocked = allocations.isNotEmpty(),
            blockReason = if (allocations.isNotEmpty()) "Atualizando valor..." else null,
            itemQuoteCacheVersion = _uiState.value.itemQuoteCacheVersion + 1
        )
        if (allocations.isNotEmpty()) {
            // DINHEIRO is the existing read-only prequote context.  The
            // selected quantity and all financial values still come from the
            // server response; this does not authorize a payment.
            requestItemsPaymentQuote(allocations, "DINHEIRO", requestVersion)
        }
    }

    fun acknowledgePaymentSuccess() {
        selectedQuantitiesByItemId.clear()
        itemQuoteCache.clear()
        _uiState.value = _uiState.value.copy(
            paymentSuccess = false,
            lastPaymentMethod = null,
            lastPaymentAmount = 0.0,
            lastPaymentCurrency = null
        )
        if (_uiState.value.splitMode == 2) {
            setupItemsSplit()
        }
        calculateFinalAmount()
    }

    data class PreparedCheckoutResult(
        val operationKey: String,
        val request: CommandCheckoutCommitRequest
    )

    fun buildCommitRequest(
        method: PaymentMethod,
        manualAmount: Double? = null,
        manualCurrency: String? = null,
        manualBaseAmount: Double? = null,
        suppliedQuote: SelectedPaymentQuote? = null
    ): CommandCheckoutCommitRequest {
        val currentTable = table ?: throw IllegalStateException("Table is null")
        val cm = CurrencyManager.getInstance()
        val baseCurrency = requireNotNull(comandaBaseCurrency?.takeIf { it.isNotBlank() } ?: _uiState.value.baseCurrency?.takeIf { it.isNotBlank() }) {
            "COMANDA_BASE_CURRENCY_NOT_LOADED: Base currency not loaded from backend"
        }
        val currentCurrency = manualCurrency ?: suppliedQuote?.transactionCurrency ?: cm.selectedCurrency

        val authoritativeItemsQuote = if (_uiState.value.splitMode == 2) {
            _uiState.value.itemQuote ?: run {
                if (itemsToPay.any { it.selected && it.item.product.selling_price == null }) {
                    throw IllegalStateException("ITEM_SPLIT_UNKNOWN_PRICE")
                }
                throw IllegalStateException("PAYMENT_QUOTE_REQUIRED")
            }
        } else null
        val quote = if (authoritativeItemsQuote != null) {
            val accounting = authoritativeItemsQuote.accountingTotal
                ?: throw IllegalStateException("PAYMENT_QUOTE_AMOUNT_MISSING")
            val transaction = if (method == PaymentMethod.CASH) {
                authoritativeItemsQuote.cashTender ?: throw IllegalStateException("PAYMENT_QUOTE_CASH_TENDER_MISSING")
            } else {
                authoritativeItemsQuote.transactionAmount ?: throw IllegalStateException("PAYMENT_QUOTE_TRANSACTION_AMOUNT_MISSING")
            }
            MoneyQuote(
                transactionAmount = MoneyDecimal.of(transaction),
                transactionCurrency = authoritativeItemsQuote.transactionCurrency ?: currentCurrency,
                baseAmount = MoneyDecimal.of(accounting),
                baseCurrency = authoritativeItemsQuote.currency ?: baseCurrency,
                fxRate = MoneyDecimal.of(authoritativeItemsQuote.fxRate ?: 1.0)
            )
        } else if (suppliedQuote != null) {
            suppliedQuote.toMoneyQuote()
        } else {
            val amountToPayBigDecimal = if (manualAmount != null) {
                MoneyDecimal.of(manualAmount)
            } else {
                MoneyDecimal.of(_uiState.value.finalToPay)
            }

            cm.quoteBaseAmount(
                baseAmount = amountToPayBigDecimal,
                baseCurrency = baseCurrency,
                transactionCurrency = currentCurrency
            ).getOrElse {
                throw IllegalStateException(it.message ?: "FX_RATE_MISSING")
            }
        }

        if (manualBaseAmount != null && _uiState.value.splitMode != 2) {
            val suppliedBase = MoneyDecimal.roundToCurrency(MoneyDecimal.of(manualBaseAmount), baseCurrency)
            if (suppliedBase.compareTo(quote.baseAmount) != 0) {
                throw IllegalStateException("MONEY_AMOUNT_MISMATCH: Supplied base $suppliedBase != quote base ${quote.baseAmount}")
            }
        }

        val digits = requireNotNull(_uiState.value.baseMinorUnitDigits) {
            "FROZEN_DIGITS_MISSING: baseMinorUnitDigits is required for monetary operations"
        }
        val balanceBaseMinor = _uiState.value.balanceBaseMinor ?: 0L
        val pendingBase = ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(balanceBaseMinor, digits)
        val remaining = pendingBase.subtract(quote.baseAmount)
        val isFinalPayment = remaining.compareTo(BigDecimal.ZERO) <= 0

        val shouldRegisterSale = when (_uiState.value.splitMode) {
            0 -> true
            1 -> isFinalPayment
            2 -> true
            else -> true
        }

        val saleItems = if (_uiState.value.splitMode == 2) {
            null
        } else if (_uiState.value.splitMode == 1) {
            currentTable.items.filter { !it.removed }
                .map { SaleItem(it.product.id, it.product.name, it.quantity, it.product.selling_price ?: 0.0) }
        } else {
            currentTable.items.filter { !it.removed && it.quantity > it.paidQuantity }
                .map { SaleItem(it.product.id, it.product.name, it.quantity - it.paidQuantity, it.product.selling_price ?: 0.0) }
        }

        val sfAmount2 = if (_uiState.value.splitMode == 2 || manualAmount != null || suppliedQuote != null) 0.0 else _uiState.value.serviceFeeAmount
        val sfKind2 = if (manualAmount != null || suppliedQuote != null) null else (_uiState.value.serviceFeeKind ?: if (sfAmount2 > 0) "fixed" else null)

        return CommandCheckoutCommitRequest(
            comandaId = effectiveComandaId(currentTable) ?: "",
            mesaId = currentTable.id,
            forma = method.apiValue,
            valor = quote.transactionAmount,
            moeda = quote.transactionCurrency,
            valorBase = if (_uiState.value.splitMode == 2) null else quote.baseAmount,
            baseCurrency = quote.baseCurrency,
            fxRate = quote.fxRate,
            exchangeRatesSnapshot = quote.snapshot,
            shouldRegisterSale = shouldRegisterSale,
            saleItems = saleItems,
            discount = BigDecimal.ZERO,
            serviceFee = MoneyDecimal.of(sfAmount2),
            serviceFeeKind = sfKind2,
            items = if (_uiState.value.splitMode == 2) {
                itemsToPay.filter { it.selected && it.selectedQuantity > 0 }.map {
                    ComandaItemAllocation(
                        requireNotNull(it.item.id) { "ITEM_SPLIT_ID_MISSING" },
                        it.selectedQuantity
                    )
                }
            } else null
        )
    }

    suspend fun prepareCheckoutOperation(
        method: PaymentMethod,
        manualAmount: Double? = null,
        manualCurrency: String? = null,
        manualBaseAmount: Double? = null,
        suppliedQuote: SelectedPaymentQuote? = null
    ): PreparedCheckoutResult {
        if (!moneyAuthorityLoaded || comandaBaseCurrency.isNullOrBlank() || _uiState.value.moneyAuthorityState != MoneyAuthorityState.READY_REMOTE || _uiState.value.isPayButtonBlocked || _uiState.value.requiresReconciliation) {
            throw IllegalStateException("PAYMENT_MUTATION_FORBIDDEN: Authoritative remote checkout required")
        }
        if (_uiState.value.splitMode == 2) {
            if (_uiState.value.itemQuote == null || _uiState.value.itemQuoteLoading || _uiState.value.itemQuoteError) {
                throw IllegalStateException("PAYMENT_QUOTE_REQUIRED")
            }
        }
        val finalRequest = buildCommitRequest(method, manualAmount, manualCurrency, manualBaseAmount, suppliedQuote)
        val key = UUID.randomUUID().toString()
        val gson = Gson()

        val entity = OutboxOperationEntity(
            id = key,
            operationType = "COMANDA_CHECKOUT_COMMIT",
            targetGroupKey = finalRequest.comandaId,
            payloadJson = gson.toJson(finalRequest),
            createdAt = System.currentTimeMillis(),
            idempotencyKey = key,
            status = "WAITING_PAYMENT"
        )

        outboxDao.insert(entity)
        Log.d("CheckoutViewModel", "Operação de checkout K=$key persistida como WAITING_PAYMENT antes do deeplink")

        withContext(Dispatchers.Main) {
            _uiState.value = _uiState.value.copy(
                isAwaitingProvider = true,
                isCashProcessing = false,
                isPendingSync = false,
                isPayButtonBlocked = true,
                blockReason = "Aguardando pagamento..."
            )
        }
        return PreparedCheckoutResult(key, finalRequest)
    }

    /** Clears volatile checkout gating after an explicit provider terminal result. */
    fun onProviderPaymentFinished(status: String?) {
        val terminal = status.equals("CANCELLED", true) ||
            status.equals("CANCELED", true) ||
            status.equals("ABORTED", true) ||
            status.equals("REJECTED", true) ||
            status.equals("FAILED_TO_START", true) ||
            status.equals("ERROR", true)
        if (!terminal) return
        _uiState.value = _uiState.value.copy(
            isLoading = false,
            isAwaitingProvider = false,
            isCashProcessing = false,
            isPendingSync = false,
            isPayButtonBlocked = false,
            requiresReconciliation = false,
            paymentSuccess = false,
            blockReason = null,
            error = null
        )
    }

    fun finalizeApprovedCheckout(checkoutOperationId: String, paymentId: String?, method: PaymentMethod) {
        val gson = Gson()
        _uiState.value = _uiState.value.copy(isLoading = true, isAwaitingProvider = false, isCashProcessing = false, error = null)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val op = outboxDao.getById(checkoutOperationId)
                if (op != null) {
                    val request = gson.fromJson(op.payloadJson, CommandCheckoutCommitRequest::class.java)
                    val updatedRequest = request.copy(
                        referenciaExterna = paymentId ?: request.referenciaExterna,
                        forma = method.apiValue
                    )

                    val updatedOp = op.copy(
                        payloadJson = gson.toJson(updatedRequest),
                        status = "PENDING"
                    )

                    outboxDao.update(updatedOp)
                    Log.d("CheckoutViewModel", "Operação K=$checkoutOperationId promovida para PENDING com referencia_externa=$paymentId")

                    outboxSyncManager.triggerSync()
                    saleSyncScheduler.scheduleSync(context)

                    withContext(Dispatchers.Main) {
                        fetchComandaPayments()
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isAwaitingProvider = false,
                            isCashProcessing = false,
                            paymentSuccess = false,
                            isPendingSync = true,
                            isPayButtonBlocked = true,
                            blockReason = "Pagamento aprovado aguardando sincronização com o servidor",
                            lastPaymentMethod = method.apiValue,
                            lastPaymentAmount = request.valor?.toDouble() ?: 0.0,
                            lastPaymentCurrency = request.moeda
                        )
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "Operação de checkout não encontrada no Room: K=$checkoutOperationId")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("CheckoutViewModel", "Erro ao promover checkout K=$checkoutOperationId: ${e.message}")
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Erro ao promover checkout: ${e.message}")
                }
            }
        }
    }

    fun finalizePayment(
        method: PaymentMethod,
        manualAmount: Double? = null,
        manualCurrency: String? = null,
        manualBaseAmount: Double? = null,
        suppliedQuote: SelectedPaymentQuote? = null
    ) {
        if (!moneyAuthorityLoaded || comandaBaseCurrency.isNullOrBlank() || _uiState.value.moneyAuthorityState != MoneyAuthorityState.READY_REMOTE || _uiState.value.isPayButtonBlocked || _uiState.value.requiresReconciliation) {
            _uiState.value = _uiState.value.copy(
                error = "Não foi possível iniciar pagamento: autorização remota necessária"
            )
            return
        }
        val gson = Gson()
        _uiState.value = _uiState.value.copy(isLoading = true, isCashProcessing = method == PaymentMethod.CASH, error = null)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val finalRequest = buildCommitRequest(method, manualAmount, manualCurrency, manualBaseAmount, suppliedQuote)
                val key = UUID.randomUUID().toString()

                val entity = OutboxOperationEntity(
                    id = key,
                    operationType = "COMANDA_CHECKOUT_COMMIT",
                    targetGroupKey = finalRequest.comandaId,
                    payloadJson = gson.toJson(finalRequest),
                    createdAt = System.currentTimeMillis(),
                    idempotencyKey = key,
                    status = "PENDING"
                )

                outboxDao.insert(entity)
                Log.d("CheckoutViewModel", "Operação de checkout manual K=$key persistida na Outbox (PENDING)")

                outboxSyncManager.triggerSync()
                saleSyncScheduler.scheduleSync(context)

                withContext(Dispatchers.Main) {
                    fetchComandaPayments()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        paymentSuccess = false,
                        isCashProcessing = method == PaymentMethod.CASH,
                        isPendingSync = true,
                        isPayButtonBlocked = true,
                        blockReason = "Pagamento em sincronização com o servidor",
                        lastPaymentMethod = method.apiValue,
                        lastPaymentAmount = finalRequest.valor?.toDouble() ?: 0.0,
                        lastPaymentCurrency = finalRequest.moeda
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("CheckoutViewModel", "Erro ao executar checkout: ${e.message}")
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isLoading = false, isCashProcessing = false, isPendingSync = false, isPayButtonBlocked = false, blockReason = null, error = "Erro ao executar checkout: ${e.message}")
                }
            }
        }
    }
}
