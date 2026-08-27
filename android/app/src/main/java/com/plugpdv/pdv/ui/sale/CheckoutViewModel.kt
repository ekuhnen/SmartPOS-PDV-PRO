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
    private val taxRepository: TaxRepository
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

    fun finalizePayment(method: PaymentMethod, manualAmount: Double? = null) {
        val currentToken = token
        val currentTable = table
        val prefs = context.getSharedPreferences(com.plugpdv.pdv.utils.Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val currentSessionId = sessionId ?: prefs.getString(com.plugpdv.pdv.utils.Constants.SESSION_ID, null)

        if (currentToken == null || currentTable == null || currentSessionId == null) {
            val missing = mutableListOf<String>()
            if (currentToken == null) missing.add("Token")
            if (currentTable == null) missing.add("Mesa")
            if (currentSessionId == null) missing.add("Sessão de Caixa")
            _uiState.value = _uiState.value.copy(
                error = "Não é possível finalizar o pagamento: ausência de ${missing.joinToString(", ")}"
            )
            return
        }

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        
        val amountToPay = manualAmount ?: _uiState.value.finalToPay
        val baseAmountToPay = if (manualAmount != null) {
            manualAmount
        } else {
            _uiState.value.currentToPay
        }

        val cm = CurrencyManager.getInstance()
        val currentCurrency = cm.selectedCurrency
        val convertedTotal = cm.convert(amountToPay)

        val isFinalPayment = (currentTable.getPendingBalance() - baseAmountToPay) <= 0.01

        val shouldRegisterSale = when (_uiState.value.splitMode) {
            0 -> true // Pagamento total sempre registra venda
            1 -> isFinalPayment // Dividir por pessoas: apenas registra venda no fechamento final
            2 -> true // Dividir por itens: sempre registra a venda correspondente aos itens selecionados
            else -> true
        }

        val saleItems = if (_uiState.value.splitMode == 2) {
            itemsToPay.filter { it.selected }.map { SaleItem(it.item.product.id, it.item.product.name, it.selectedQuantity, it.item.product.selling_price ?: 0.0) }
        } else if (_uiState.value.splitMode == 1) {
            // Se for dividir por pessoas e for o pagamento final, envia todos os itens da mesa
            currentTable.items.filter { !it.removed }
                .map { SaleItem(it.product.id, it.product.name, it.quantity, it.product.selling_price ?: 0.0) }
        } else {
            currentTable.items.filter { !it.removed && it.quantity > it.paidQuantity }
                .map { SaleItem(it.product.id, it.product.name, it.quantity - it.paidQuantity, it.product.selling_price ?: 0.0) }
        }

        var taxPercentage = 0.0
        _uiState.value.activeTaxes.filter { it.currency.equals(currentCurrency, ignoreCase = true) }.forEach {
            taxPercentage += it.percentage
        }
        val fullTableBase = currentTable.calculateTotal()
        val fullTableTax = if (taxPercentage > 0) fullTableBase * (taxPercentage / 100.0) else 0.0
        val fullTableTotal = fullTableBase + fullTableTax

        val sfAmount1 = _uiState.value.serviceFeeAmount
        val sfKind1 = _uiState.value.serviceFeeKind ?: if (sfAmount1 > 0) "fixed" else null

        val sfAmount2 = if (manualAmount != null) 0.0 else _uiState.value.serviceFeeAmount
        val sfKind2 = if (manualAmount != null) null else (_uiState.value.serviceFeeKind ?: if (sfAmount2 > 0) "fixed" else null)

        val saleRequest = if (_uiState.value.splitMode == 1) {
            SaleRequest(
                customerName = currentTable.customerName,
                total = fullTableTotal,
                items = saleItems,
                paymentMethod = method.apiValue,
                currency = currentCurrency,
                caixa_session_id = currentSessionId,
                operatorId = operatorId,
                operatorName = operatorName,
                taxAmount = fullTableTax,
                serviceFeeAmount = sfAmount1,
                serviceFeeKind = sfKind1,
                convertedTotal = cm.convert(fullTableTotal)
            )
        } else {
            SaleRequest(
                customerName = currentTable.customerName,
                total = amountToPay,
                items = saleItems,
                paymentMethod = method.apiValue,
                currency = currentCurrency,
                caixa_session_id = currentSessionId,
                operatorId = operatorId,
                operatorName = operatorName,
                taxAmount = if (manualAmount != null) 0.0 else _uiState.value.taxAmount,
                serviceFeeAmount = sfAmount2,
                serviceFeeKind = sfKind2,
                convertedTotal = convertedTotal
            )
        }

        viewModelScope.launch {
            try {
                Log.d("CheckoutViewModel", "Iniciando finalização de pagamento MESA: ${method.apiValue} - Valor: $amountToPay")
                
                // 1. Registrar a venda fiscal se necessário
                if (shouldRegisterSale) {
                    val idempotencyKey = java.util.UUID.randomUUID().toString()
                    retryIO { apiService.registerSale("Bearer $currentToken", idempotencyKey, saleRequest) }
                    Log.d("CheckoutViewModel", "Venda fiscal registrada com sucesso.")
                }
                
                // 2. Registrar o pagamento na comanda (convertendo para a moeda da transação)
                val amountInSelectedCurrency = cm.fromBrl(baseAmountToPay, currentCurrency)
                val finalAmountForAction = if (currentCurrency.equals("PYG", ignoreCase = true) || currentCurrency.equals("ARS", ignoreCase = true)) {
                    Math.ceil(amountInSelectedCurrency)
                } else {
                    amountInSelectedCurrency
                }

                val paymentAction = CommandActionRequest().apply {
                    action = "add_pagamento"
                    comandaId = currentTable.comandaId
                    mesaId = currentTable.id
                    paymentForm = method.apiValue
                    currency = currentCurrency
                    amount = finalAmountForAction
                }
                val response = retryIO { apiService.manageComanda("Bearer $currentToken", paymentAction) }
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string() ?: ""
                    throw Exception("Erro ao registrar pagamento: ${response.code()} $errorBody")
                }
                Log.d("CheckoutViewModel", "Pagamento na comanda registrado com sucesso.")

                // 3. Atualizar estado local e buscar o estado mais recente de pagamentos no servidor
                processLocalPayment(currentToken)
                fetchComandaPayments()
                
                _uiState.value = _uiState.value.copy(
                    isLoading = false, 
                    paymentSuccess = true,
                    fullTableTotalPaid = fullTableTotal,
                    lastPaymentMethod = method.apiValue,
                    lastPaymentAmount = amountToPay
                )
            } catch (e: retrofit2.HttpException) {
                val errorBody = e.response()?.errorBody()?.string() ?: e.message()
                Log.e("CheckoutViewModel", "FALHA NO PAGAMENTO MESA (HTTP ${e.code()}): $errorBody")
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Erro ao registrar venda: HTTP ${e.code()} $errorBody")
            } catch (e: Exception) {
                Log.e("CheckoutViewModel", "FALHA NO PAGAMENTO MESA: ${e.message}")
                e.printStackTrace()
                _uiState.value = _uiState.value.copy(isLoading = false, error = "Erro ao registrar venda: ${e.message}")
            }
        }
    }

    private suspend fun processLocalPayment(currentToken: String) {
        val currentTable = table ?: return
        val state = _uiState.value
        val savedComandaId = currentTable.comandaId
        val savedTableId = currentTable.id

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

        val isFullyPaid = currentTable.getPendingBalance() <= 0.01

        if (isFullyPaid) {
            if (!savedComandaId.isNullOrEmpty() && !savedTableId.isNullOrEmpty()) {
                val request = CommandActionRequest().apply {
                    action = "fechar"
                    comandaId = savedComandaId
                    mesaId = savedTableId
                }
                val response = retryIO { apiService.manageComanda("Bearer $currentToken", request) }
                if (!response.isSuccessful) {
                    val errorBody = response.errorBody()?.string() ?: ""
                    Log.e("CheckoutViewModel", "Erro ao fechar mesa no servidor: ${response.code()} $errorBody")
                } else {
                    Log.d("CheckoutViewModel", "Mesa fechada no servidor síncronamente.")
                }
            }

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
