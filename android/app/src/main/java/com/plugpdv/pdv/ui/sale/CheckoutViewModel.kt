package com.plugpdv.pdv.ui.sale

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.TaxEntity
import com.plugpdv.pdv.models.*
import com.plugpdv.pdv.repository.TaxRepository
import android.content.Context
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.PaymentMethod
import com.plugpdv.pdv.utils.ServiceFeeManager
import com.plugpdv.pdv.utils.TableManager
import com.plugpdv.pdv.utils.retryIO
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject

data class CheckoutUiState(
    val isLoading: Boolean = false,
    val error: String? = null,
    val paymentSuccess: Boolean = false,
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

@HiltViewModel
class CheckoutViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val apiService: PosApiService,
    private val taxRepository: TaxRepository,
    private val outboxDao: com.plugpdv.pdv.database.OutboxDao,
    private val outboxSyncManager: com.plugpdv.pdv.utils.OutboxSyncManager,
    private val saleSyncScheduler: com.plugpdv.pdv.outbox.SaleSyncScheduler
) : ViewModel() {

    private val _uiState = MutableStateFlow(CheckoutUiState())
    val uiState: StateFlow<CheckoutUiState> = _uiState.asStateFlow()

    private var table: Table? = null
    private var token: String? = null
    private var sessionId: String? = null
    private var operatorId: String? = null
    private var operatorName: String? = null

    // Split by items tracking
    val itemsToPay = mutableListOf<TableItemPayment>()

    fun init(table: Table, token: String, sessionId: String?, opId: String?, opName: String?) {
        this.table = table
        this.token = token
        this.sessionId = sessionId
        this.operatorId = opId
        this.operatorName = opName
        
        val serviceFeeConfig = ServiceFeeManager.getConfig(context)
        _uiState.value = _uiState.value.copy(
            currentToPay = table.getPendingBalance(),
            serviceFeeConfig = serviceFeeConfig,
            serviceFeeKind = if (serviceFeeConfig?.fixedEnabled == true) "fixed" else null
        )
        
        fetchComandaPayments()

        viewModelScope.launch {
            taxRepository.getActiveTaxesLiveData().observeForever { taxes ->
                _uiState.value = _uiState.value.copy(activeTaxes = taxes)
                calculateFinalAmount()
            }
        }
    }

    fun fetchComandaPayments() {
        val currentTable = table ?: return
        val currentToken = token ?: return
        val cId = currentTable.comandaId ?: return
        val cm = CurrencyManager.getInstance()
        val currentCurrency = cm.selectedCurrency

        viewModelScope.launch {
            try {
                val detail = retryIO { apiService.getComandaDetail("Bearer $currentToken", cId) }
                val serverPaidInCurrency = if (detail.totalPago > 0) detail.totalPago else detail.pagamentos.sumOf { it.valor }
                val serverPaidBrl = cm.toBrl(serverPaidInCurrency, currentCurrency)
                if (serverPaidBrl > 0) {
                    currentTable.paidAmount = serverPaidBrl
                    TableManager.updateTable(currentTable)
                }
                _uiState.value = _uiState.value.copy(
                    paymentsHistory = detail.pagamentos,
                    currentToPay = currentTable.getPendingBalance()
                )
                calculateFinalAmount()
            } catch (e: Exception) {
                Log.e("CheckoutViewModel", "Falha ao buscar pagamentos da comanda: ${e.message}")
            }
        }
    }

    fun setSplitMode(mode: Int) {
        _uiState.value = _uiState.value.copy(splitMode = mode)
        when (mode) {
            0 -> _uiState.value = _uiState.value.copy(currentToPay = table?.getPendingBalance() ?: 0.0)
            1 -> updatePeopleSplit(1) // Default 1 person
            2 -> setupItemsSplit()
        }
        calculateFinalAmount()
    }

    fun updatePeopleSplit(count: Int) {
        val currentTable = table ?: return
        if (count > 0) {
            val fullBalance = currentTable.getPendingBalance() + currentTable.paidAmount
            _uiState.value = _uiState.value.copy(currentToPay = fullBalance / count)
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
        itemsToPay[position].selected = isSelected
        calculateItemsTotal()
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

    private fun buildCommitRequest(method: PaymentMethod, manualAmount: Double? = null): CommandCheckoutCommitRequest {
        val currentTable = table ?: throw IllegalStateException("Table is null")
        val amountToPay = manualAmount ?: _uiState.value.finalToPay
        val baseAmountToPay = manualAmount ?: _uiState.value.currentToPay
        val cm = CurrencyManager.getInstance()
        val currentCurrency = cm.selectedCurrency
        val isFinalPayment = (currentTable.getPendingBalance() - baseAmountToPay) <= 0.01

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

        val sfAmount2 = if (manualAmount != null) 0.0 else _uiState.value.serviceFeeAmount
        val sfKind2 = if (manualAmount != null) null else (_uiState.value.serviceFeeKind ?: if (sfAmount2 > 0) "fixed" else null)

        return CommandCheckoutCommitRequest(
            comandaId = currentTable.comandaId ?: "",
            mesaId = currentTable.id,
            forma = method.apiValue,
            valor = amountToPay,
            moeda = currentCurrency,
            shouldRegisterSale = shouldRegisterSale,
            saleItems = saleItems,
            discount = 0.0,
            serviceFee = sfAmount2,
            serviceFeeKind = sfKind2,
            valorBase = baseAmountToPay
        )
    }

    suspend fun prepareCheckoutOperation(method: PaymentMethod, manualAmount: Double? = null): String {
        val request = buildCommitRequest(method, manualAmount)
        val key = java.util.UUID.randomUUID().toString()
        val gson = com.google.gson.Gson()

        val entity = com.plugpdv.pdv.database.OutboxOperationEntity(
            id = key,
            operationType = "COMANDA_CHECKOUT_COMMIT",
            targetGroupKey = request.comandaId,
            payloadJson = gson.toJson(request),
            createdAt = System.currentTimeMillis(),
            idempotencyKey = key,
            status = "WAITING_PAYMENT"
        )

        outboxDao.insert(entity)
        Log.d("CheckoutViewModel", "Operação de checkout K=$key persistida como WAITING_PAYMENT antes do deeplink")
        return key
    }

    fun finalizeApprovedCheckout(checkoutOperationId: String, paymentId: String?, method: PaymentMethod) {
        val gson = com.google.gson.Gson()
        val amountToPay = _uiState.value.finalToPay
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
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

                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        fetchComandaPayments()
                        _uiState.value = _uiState.value.copy(
                            isLoading = false,
                            paymentSuccess = true,
                            lastPaymentMethod = method.apiValue,
                            lastPaymentAmount = amountToPay
                        )
                    }
                } else {
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        _uiState.value = _uiState.value.copy(isLoading = false, error = "Operação de checkout não encontrada no Room: K=$checkoutOperationId")
                    }
                }
            } catch (e: Exception) {
                Log.e("CheckoutViewModel", "Erro ao promover checkout K=$checkoutOperationId: ${e.message}")
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Erro ao promover checkout: ${e.message}")
                }
            }
        }
    }

    fun finalizePayment(method: PaymentMethod, manualAmount: Double? = null) {
        val gson = com.google.gson.Gson()
        val amountToPay = manualAmount ?: _uiState.value.finalToPay

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val request = buildCommitRequest(method, manualAmount)
                val key = java.util.UUID.randomUUID().toString()

                val entity = com.plugpdv.pdv.database.OutboxOperationEntity(
                    id = key,
                    operationType = "COMANDA_CHECKOUT_COMMIT",
                    targetGroupKey = request.comandaId,
                    payloadJson = gson.toJson(request),
                    createdAt = System.currentTimeMillis(),
                    idempotencyKey = key,
                    status = "PENDING"
                )

                outboxDao.insert(entity)
                Log.d("CheckoutViewModel", "Operação de checkout DINHEIRO K=$key enfileirada no Room")

                outboxSyncManager.triggerSync()
                saleSyncScheduler.scheduleSync(context)

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    fetchComandaPayments()
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        paymentSuccess = true,
                        lastPaymentMethod = method.apiValue,
                        lastPaymentAmount = amountToPay
                    )
                }
            } catch (e: Exception) {
                Log.e("CheckoutViewModel", "Erro ao registrar checkout em dinheiro: ${e.message}")
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isLoading = false, error = "Erro ao registrar checkout: ${e.message}")
                }
            }
        }
    }

    private fun processLocalPaymentResult(commitRes: ComandaCheckoutCommitResponse?) {
        val currentTable = table ?: return
        val state = _uiState.value

        when (state.splitMode) {
            0 -> {
                currentTable.paidAmount = currentTable.calculateTotal()
            }
            1 -> {
                currentTable.paidAmount += state.currentToPay
            }
            2 -> {
                itemsToPay.filter { it.selected }.forEach { tip ->
                    val paidValue = (tip.item.product.selling_price ?: 0.0) * tip.selectedQuantity
                    currentTable.paidAmount += paidValue
                    
                    tip.item.paidQuantity += tip.selectedQuantity
                    if (tip.item.paidQuantity >= tip.item.quantity) {
                        tip.item.isPaid = true
                    }
                }
            }
        }

        val isFullyPaidOnServer = commitRes?.closed == true || commitRes?.comandaStatus.equals("FECHADA", ignoreCase = true)

        if (isFullyPaidOnServer) {
            currentTable.status = Table.Status.AVAILABLE
            currentTable.comandaId = null
            currentTable.customerName = ""
            currentTable.paidAmount = 0.0
            currentTable.items.clear()
        }

        // SALVAR ESTADO LOCAL NA BASE DO TABLE_MANAGER
        TableManager.updateTable(currentTable)
    }
}

class TableItemPayment(val item: TableItem) {
    var selected: Boolean = false
    var selectedQuantity: Int = item.quantity - item.paidQuantity
}
