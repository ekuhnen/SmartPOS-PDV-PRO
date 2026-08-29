package com.plugpdv.pdv.ui.sale

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.api.PosApiService
import com.google.gson.Gson
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
    val baseMinorUnitDigits: Int = 2,
    val totalBaseMinor: Long? = null,
    val paidBaseMinor: Long? = null,
    val balanceBaseMinor: Long? = null,
    val refreshWarning: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val paymentSuccess: Boolean = false,
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
    val serviceFeeConfig: ServiceFeeConfig? = null,
    val serviceFeeAmount: Double = 0.0,
    val serviceFeeKind: String? = null,
    val serviceFeeManualValue: Double = 0.0,
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

    var comandaBaseCurrency: String? = null
    var moneyAuthorityLoaded: Boolean = false

    // Split by items tracking
    val itemsToPay = mutableListOf<TableItemPayment>()

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
    ) {
        this.token = token
        this.sessionId = sessionId
        this.operatorId = opId
        this.operatorName = opName

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
                currentTable = if (!tableId.isNullOrEmpty()) {
                    tableReadRepository.getTableById(tableId)
                } else if (tableNumber > 0) {
                    tableReadRepository.getTableByNumber(tableNumber, sectorId)
                } else {
                    null
                }
                this@CheckoutViewModel.table = currentTable
            }

            if (currentTable == null) {
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                    isPayButtonBlocked = true,
                    blockReason = "Mesa não encontrada no banco de dados.",
                    error = "Mesa não encontrada."
                )
                return@launch
            }

            // 2. Evaluate durable blockers from Room
            val durableBlock = checkDurablePaymentBlockers(currentTable)

            // 3. Load & evaluate local snapshot
            val localSnapshot = loadLocalSnapshot(currentTable)
            val cId = currentTable.comandaId.orEmpty()
            val localAuthorityDecision = if (localSnapshot != null && cId.isNotEmpty()) {
                ComandaSnapshotAuthorityPolicy.evaluate(localSnapshot, cId, context)
            } else {
                SnapshotAuthorityDecision.MISSING_AUTHORITY
            }

            // 4. Apply local authority state deterministically
            applyLocalAuthorityState(localSnapshot, localAuthorityDecision, durableBlock)

            // 5. Perform remote refresh sequentially
            performRemoteRefresh(currentTable, localAuthorityDecision, durableBlock)
        }

        // Listen for checkout sync events
        viewModelScope.launch {
            outboxSyncManager.checkoutResultEvents.collect { event ->
                val cId = table?.comandaId
                if (cId != null && event.comandaId == cId) {
                    if (event.requiresReconciliation) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isPendingSync = false,
                            isPayButtonBlocked = true,
                            paymentSuccess = false,
                            requiresReconciliation = true,
                            blockReason = "Pagamento aprovado requer conciliação"
                        )
                    } else if (event.closed) {
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            isPendingSync = false,
                            isPayButtonBlocked = false,
                            paymentSuccess = true,
                            requiresReconciliation = false,
                            blockReason = null
                        )
                    } else {
                        fetchComandaPayments()
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
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

        // Active taxes
        viewModelScope.launch {
            taxRepository.getActiveTaxesLiveData().observeForever { taxes ->
                _uiState.value = _uiState.value.copy(activeTaxes = taxes)
                calculateFinalAmount()
            }
        }
    }

    suspend fun checkDurablePaymentBlockers(currentTable: Table): DurableBlockerResult = withContext(Dispatchers.IO) {
        val cId = currentTable.comandaId ?: return@withContext DurableBlockerResult()

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
        val cId = currentTable.comandaId ?: return@withContext null
        val tenantId = TenantBindingStore.getActiveTenantId(context) ?: return@withContext null
        comandaSnapshotRepository.getByServerComandaId(tenantId, cId)
    }

    private fun applyLocalAuthorityState(
        snapshot: ComandaSnapshotEntity?,
        decision: SnapshotAuthorityDecision,
        durableBlock: DurableBlockerResult
    ) {
        if (snapshot == null) return

        val digits = snapshot.baseMinorUnitDigits ?: 2
        val balDecimal = snapshot.balanceBaseMinor?.let {
            ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits)
        } ?: BigDecimal.ZERO

        when (decision) {
            SnapshotAuthorityDecision.USABLE -> {
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
                    currentToPay = balDecimal.toDouble(),
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
                    currentToPay = balDecimal.toDouble(),
                    isPendingSync = durableBlock.isPendingSync,
                    requiresReconciliation = true,
                    isPayButtonBlocked = true,
                    blockReason = durableBlock.reason ?: "Pagamento aprovado requer conciliação"
                )
                calculateFinalAmount()
            }
            else -> {
                // Other non-usable local decisions remain in LOADING or durable blocker state
                if (durableBlock.isBlocked) {
                    _uiState.value = _uiState.value.copy(
                        isPayButtonBlocked = true,
                        isPendingSync = durableBlock.isPendingSync,
                        requiresReconciliation = durableBlock.requiresReconciliation,
                        blockReason = durableBlock.reason
                    )
                }
            }
        }
    }

    fun fetchComandaPayments() {
        val currentTable = table ?: return
        viewModelScope.launch {
            val durableBlock = checkDurablePaymentBlockers(currentTable)
            val localSnapshot = loadLocalSnapshot(currentTable)
            val cId = currentTable.comandaId.orEmpty()
            val localAuthorityDecision = if (localSnapshot != null && cId.isNotEmpty()) {
                ComandaSnapshotAuthorityPolicy.evaluate(localSnapshot, cId, context)
            } else {
                SnapshotAuthorityDecision.MISSING_AUTHORITY
            }
            performRemoteRefresh(currentTable, localAuthorityDecision, durableBlock)
        }
    }

    private suspend fun performRemoteRefresh(
        currentTable: Table,
        localAuthorityDecision: SnapshotAuthorityDecision,
        durableBlock: DurableBlockerResult
    ) {
        val currentToken = token ?: return
        val cId = currentTable.comandaId ?: return

        try {
            val detail = retryIO { apiService.getComandaDetail("Bearer $currentToken", cId) }
            val cachedSnapshot = try {
                comandaSnapshotRepository.cacheRemoteDetail(detail, currentTable)
            } catch (e: Exception) {
                Log.e("CheckoutViewModel", "Non-fatal failure caching comanda snapshot: ${e.message}", e)
                null
            }

            val snapshotToEvaluate = cachedSnapshot ?: loadLocalSnapshot(currentTable)
            val decision = if (snapshotToEvaluate != null) {
                ComandaSnapshotAuthorityPolicy.evaluate(snapshotToEvaluate, cId, context)
            } else {
                SnapshotAuthorityDecision.MISSING_AUTHORITY
            }

            val digits = snapshotToEvaluate?.baseMinorUnitDigits ?: 2
            val baseCurrency = snapshotToEvaluate?.baseCurrency ?: detail.baseCurrency
            val totalBaseMinor = snapshotToEvaluate?.totalBaseMinor ?: (if (!baseCurrency.isNullOrBlank()) ComandaSnapshotRepository.toMinorUnitsWithFrozenScale(BigDecimal.valueOf(detail.total ?: 0.0), digits) else null)
            val paidBaseMinor = snapshotToEvaluate?.paidBaseMinor ?: (if (!baseCurrency.isNullOrBlank()) ComandaSnapshotRepository.toMinorUnitsWithFrozenScale(BigDecimal.valueOf(detail.totalPagoBase ?: detail.totalPago ?: 0.0), digits) else null)
            val balanceBaseMinor = snapshotToEvaluate?.balanceBaseMinor ?: (if (!baseCurrency.isNullOrBlank()) ComandaSnapshotRepository.toMinorUnitsWithFrozenScale(BigDecimal.valueOf(detail.saldoBase ?: ((detail.total ?: 0.0) - (detail.totalPagoBase ?: detail.totalPago ?: 0.0))), digits) else null)

            val balDecimal = balanceBaseMinor?.let {
                ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits)
            } ?: BigDecimal.ZERO

            val isComandaClosed = detail.status.equals("FECHADA", ignoreCase = true) || decision == SnapshotAuthorityDecision.CLOSED
            val requiresRecon = durableBlock.requiresReconciliation || decision == SnapshotAuthorityDecision.RECONCILIATION_REQUIRED || detail.requiresReconciliation || baseCurrency.isNullOrBlank()

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
                    currentToPay = balDecimal.toDouble(),
                    isPendingSync = durableBlock.isPendingSync,
                    isPayButtonBlocked = true,
                    requiresReconciliation = true,
                    paymentSuccess = false,
                    blockReason = durableBlock.reason ?: if (baseCurrency.isNullOrBlank()) "Moeda-base não definida no servidor" else "Pagamento aprovado requer conciliação",
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
                    isPayButtonBlocked = false,
                    paymentSuccess = true,
                    requiresReconciliation = false,
                    blockReason = null,
                    refreshWarning = null
                )
            } else {
                moneyAuthorityLoaded = true
                comandaBaseCurrency = baseCurrency
                val isBlocked = durableBlock.isBlocked
                _uiState.value = _uiState.value.copy(
                    moneyAuthorityState = MoneyAuthorityState.READY_REMOTE,
                    authoritySource = "REMOTE",
                    baseCurrency = baseCurrency,
                    baseMinorUnitDigits = digits,
                    totalBaseMinor = totalBaseMinor,
                    paidBaseMinor = paidBaseMinor,
                    balanceBaseMinor = balanceBaseMinor,
                    paymentsHistory = detail.pagamentos.orEmpty(),
                    currentToPay = balDecimal.toDouble(),
                    isPendingSync = durableBlock.isPendingSync,
                    isPayButtonBlocked = isBlocked,
                    requiresReconciliation = false,
                    paymentSuccess = false,
                    blockReason = durableBlock.reason,
                    refreshWarning = null
                )
            }
            calculateFinalAmount()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            Log.e("CheckoutViewModel", "HTTP error ${e.code()} fetching comanda detail: ${e.message}", e)
            moneyAuthorityLoaded = false
            _uiState.value = _uiState.value.copy(
                moneyAuthorityState = MoneyAuthorityState.LOAD_ERROR,
                isPayButtonBlocked = true,
                blockReason = "Erro ao carregar dados financeiros (Código: ${e.code()})",
                error = "Erro no servidor (Código: ${e.code()})"
            )
        } catch (e: Exception) {
            Log.e("CheckoutViewModel", "Network error fetching comanda payments: ${e.message}", e)
            if (localAuthorityDecision == SnapshotAuthorityDecision.USABLE) {
                // Keep READY_LOCAL, do not downgrade to LOAD_ERROR
                _uiState.value = _uiState.value.copy(
                    refreshWarning = "Sem conexão — exibindo dados salvos",
                    isPayButtonBlocked = true,
                    blockReason = durableBlock.reason ?: "Sem conexão para processar pagamento"
                )
            } else if (localAuthorityDecision == SnapshotAuthorityDecision.RECONCILIATION_REQUIRED || durableBlock.requiresReconciliation) {
                // Keep RECONCILIATION_REQUIRED, network failure must never erase reconciliation requirement
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
        val balDecimal = _uiState.value.balanceBaseMinor?.let {
            ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits)
        } ?: BigDecimal.ZERO

        when (mode) {
            0 -> _uiState.value = _uiState.value.copy(currentToPay = balDecimal.toDouble())
            1 -> updatePeopleSplit(1) // Default 1 person
            2 -> setupItemsSplit()
        }
        calculateFinalAmount()
    }

    fun updatePeopleSplit(count: Int) {
        if (count > 0) {
            val digits = _uiState.value.baseMinorUnitDigits
            val totalDecimal = _uiState.value.totalBaseMinor?.let {
                ComandaSnapshotAuthorityPolicy.fromMinorUnitsWithFrozenScale(it, digits)
            } ?: BigDecimal.ZERO
            _uiState.value = _uiState.value.copy(currentToPay = totalDecimal.toDouble() / count)
        } else {
            _uiState.value = _uiState.value.copy(currentToPay = 0.0)
        }
        calculateFinalAmount()
    }

    private fun setupItemsSplit() {
        itemsToPay.clear()
        table?.items?.filter { !it.removed && it.quantity > it.paidQuantity }?.forEach {
            itemsToPay.add(TableItemPayment(it))
        }
        _uiState.value = _uiState.value.copy(currentToPay = 0.0)
    }

    fun onItemSelected(position: Int, isSelected: Boolean) {
        if (position in 0 until itemsToPay.size) {
            itemsToPay[position].selected = isSelected
            calculateItemsTotal()
        }
    }

    private fun calculateItemsTotal() {
        var total = 0.0
        itemsToPay.filter { it.selected }.forEach {
            total += (it.item.product.selling_price ?: 0.0) * it.selectedQuantity
        }
        _uiState.value = _uiState.value.copy(currentToPay = total)
        calculateFinalAmount()
    }

    private fun calculateFinalAmount() {
        val state = _uiState.value
        val cm = CurrencyManager.getInstance()
        val currentCurrency = cm.selectedCurrency
        var taxPercentage = 0.0

        state.activeTaxes.filter { it.currency.equals(currentCurrency, ignoreCase = true) }.forEach {
            taxPercentage += it.percentage
        }

        val baseToPay = state.currentToPay.coerceAtLeast(0.0)
        val tax = if (taxPercentage > 0) baseToPay * (taxPercentage / 100.0) else 0.0

        var sfAmount = 0.0
        if (state.serviceFeeKind != null) {
            when (state.serviceFeeKind) {
                "fixed" -> {
                    val pct = state.serviceFeeConfig?.fixedPercent ?: 0.0
                    sfAmount = baseToPay * (pct / 100.0)
                }
                "manual_percent" -> {
                    sfAmount = baseToPay * (state.serviceFeeManualValue / 100.0)
                }
                "manual_value" -> {
                    sfAmount = state.serviceFeeManualValue
                }
                "waived" -> {
                    sfAmount = 0.0
                }
            }
        }

        _uiState.value = _uiState.value.copy(
            taxAmount = tax,
            serviceFeeAmount = sfAmount,
            finalToPay = (baseToPay + tax + sfAmount).coerceAtLeast(0.0)
        )
    }

    fun refreshCalculations() {
        calculateFinalAmount()
    }

    fun overrideServiceFee(kind: String, value: Double = 0.0) {
        _uiState.value = _uiState.value.copy(
            serviceFeeKind = kind,
            serviceFeeManualValue = value
        )
        calculateFinalAmount()
    }

    fun acknowledgePaymentSuccess() {
        _uiState.value = _uiState.value.copy(
            paymentSuccess = false,
            lastPaymentMethod = null,
            lastPaymentAmount = 0.0
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

        val quote = if (suppliedQuote != null) {
            suppliedQuote.toMoneyQuote()
        } else {
            val amountToPayBigDecimal = if (manualAmount != null) {
                MoneyDecimal.of(manualAmount)
            } else {
                MoneyDecimal.of(_uiState.value.finalToPay)
            }

            cm.quoteTransactionAmount(
                amountToPayBigDecimal,
                currentCurrency,
                baseCurrency
            ).getOrElse {
                throw IllegalStateException(it.message ?: "FX_RATE_MISSING")
            }
        }

        if (manualBaseAmount != null) {
            val suppliedBase = MoneyDecimal.roundToCurrency(MoneyDecimal.of(manualBaseAmount), baseCurrency)
            if (suppliedBase.compareTo(quote.baseAmount) != 0) {
                throw IllegalStateException("MONEY_AMOUNT_MISMATCH: Supplied base $suppliedBase != quote base ${quote.baseAmount}")
            }
        }

        val digits = _uiState.value.baseMinorUnitDigits
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
            itemsToPay.filter { it.selected }.map { SaleItem(it.item.product.id, it.item.product.name, it.selectedQuantity, it.item.product.selling_price ?: 0.0) }
        } else if (_uiState.value.splitMode == 1) {
            currentTable.items.filter { !it.removed }
                .map { SaleItem(it.product.id, it.product.name, it.quantity, it.product.selling_price ?: 0.0) }
        } else {
            currentTable.items.filter { !it.removed && it.quantity > it.paidQuantity }
                .map { SaleItem(it.product.id, it.product.name, it.quantity - it.paidQuantity, it.product.selling_price ?: 0.0) }
        }

        val sfAmount2 = if (manualAmount != null || suppliedQuote != null) 0.0 else _uiState.value.serviceFeeAmount
        val sfKind2 = if (manualAmount != null || suppliedQuote != null) null else (_uiState.value.serviceFeeKind ?: if (sfAmount2 > 0) "fixed" else null)

        return CommandCheckoutCommitRequest(
            comandaId = currentTable.comandaId ?: "",
            mesaId = currentTable.id,
            forma = method.apiValue,
            valor = quote.transactionAmount,
            moeda = quote.transactionCurrency,
            valorBase = quote.baseAmount,
            baseCurrency = quote.baseCurrency,
            fxRate = quote.fxRate,
            exchangeRatesSnapshot = quote.snapshot,
            shouldRegisterSale = shouldRegisterSale,
            saleItems = saleItems,
            discount = BigDecimal.ZERO,
            serviceFee = MoneyDecimal.of(sfAmount2),
            serviceFeeKind = sfKind2
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
                isPendingSync = true,
                isPayButtonBlocked = true,
                blockReason = "Pagamento aprovado aguardando sincronização com o servidor"
            )
        }
        return PreparedCheckoutResult(key, finalRequest)
    }

    fun finalizeApprovedCheckout(checkoutOperationId: String, paymentId: String?, method: PaymentMethod) {
        val gson = Gson()
        val amountToPay = _uiState.value.finalToPay

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

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
                            paymentSuccess = false,
                            isPendingSync = true,
                            isPayButtonBlocked = true,
                            blockReason = "Pagamento aprovado aguardando sincronização com o servidor",
                            lastPaymentMethod = method.apiValue,
                            lastPaymentAmount = amountToPay
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
        val amountToPay = manualAmount ?: suppliedQuote?.transactionAmount?.toDouble() ?: _uiState.value.finalToPay

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

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
                        isPendingSync = true,
                        isPayButtonBlocked = true,
                        blockReason = "Pagamento aprovado aguardando sincronização com o servidor",
                        lastPaymentMethod = method.apiValue,
                        lastPaymentAmount = amountToPay
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("CheckoutViewModel", "Erro ao executar checkout: ${e.message}")
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Erro ao executar checkout: ${e.message}")
                }
            }
        }
    }
}
