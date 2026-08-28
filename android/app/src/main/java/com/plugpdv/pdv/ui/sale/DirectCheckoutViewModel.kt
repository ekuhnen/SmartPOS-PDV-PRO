package com.plugpdv.pdv.ui.sale

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.TaxEntity
import com.plugpdv.pdv.models.SaleItem
import com.plugpdv.pdv.models.SaleRequest
import com.plugpdv.pdv.models.SaleResponse
import com.plugpdv.pdv.models.ServiceFeeConfig
import com.plugpdv.pdv.outbox.SaleSyncScheduler
import com.plugpdv.pdv.repository.SaleOutboxRepository
import com.plugpdv.pdv.repository.TaxRepository
import com.plugpdv.pdv.repository.UnresolvedDirectPaymentState
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.ServiceFeeManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

sealed class SaleResult {
    data class Success(val response: SaleResponse) : SaleResult()
    data class Error(val message: String) : SaleResult()
}

@HiltViewModel
class DirectCheckoutViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val apiService: PosApiService,
    private val taxRepository: TaxRepository,
    private val saleOutboxRepository: SaleOutboxRepository,
    private val saleSyncScheduler: SaleSyncScheduler
) : ViewModel() {

    private val gson = Gson()

    private val _cartItems = MutableLiveData<List<SaleViewModel.CartItem>>(emptyList())
    val cartItems: LiveData<List<SaleViewModel.CartItem>> = _cartItems

    private val _activeTaxes = MutableLiveData<List<TaxEntity>>(emptyList())
    val activeTaxes: LiveData<List<TaxEntity>> = _activeTaxes

    private val _baseTotal = MutableLiveData(0.0)
    val baseTotal: LiveData<Double> = _baseTotal

    private val _finalTotal = MutableLiveData(0.0)
    val finalTotal: LiveData<Double> = _finalTotal

    private val _taxAmount = MutableLiveData(0.0)
    val taxAmount: LiveData<Double> = _taxAmount

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _serviceFeeConfig = MutableLiveData<ServiceFeeConfig?>(null)
    val serviceFeeConfig: LiveData<ServiceFeeConfig?> = _serviceFeeConfig

    private val _serviceFeeAmount = MutableLiveData(0.0)
    val serviceFeeAmount: LiveData<Double> = _serviceFeeAmount

    private val _serviceFeeKind = MutableLiveData<String?>(null)
    val serviceFeeKind: LiveData<String?> = _serviceFeeKind

    private var _serviceFeeManualValue = 0.0

    private val _saleResult = MutableLiveData<SaleResult?>(null)
    val saleResult: LiveData<SaleResult?> = _saleResult

    private val _latestReceiptSnapshot = MutableStateFlow<ReceiptMoneySnapshot?>(null)
    val latestReceiptSnapshot: StateFlow<ReceiptMoneySnapshot?> = _latestReceiptSnapshot.asStateFlow()

    private val _unresolvedPaymentState = MutableStateFlow<UnresolvedDirectPaymentState?>(null)
    val unresolvedPaymentState: StateFlow<UnresolvedDirectPaymentState?> = _unresolvedPaymentState.asStateFlow()

    private val _isPaymentBlocked = MutableStateFlow(false)
    val isPaymentBlocked: StateFlow<Boolean> = _isPaymentBlocked.asStateFlow()

    private val _requiresReconciliation = MutableStateFlow(false)
    val requiresReconciliation: StateFlow<Boolean> = _requiresReconciliation.asStateFlow()

    private val _blockReason = MutableStateFlow<String?>(null)
    val blockReason: StateFlow<String?> = _blockReason.asStateFlow()

    init {
        val cached = ServiceFeeManager.getConfig(context)
        if (cached != null) {
            applyServiceFeeConfig(cached)
        }

        taxRepository.getActiveTaxesLiveData().observeForever {
            _activeTaxes.value = it ?: emptyList()
            calculateTotals()
        }
        restoreDurableRecovery()
    }

    private fun applyServiceFeeConfig(config: ServiceFeeConfig) {
        _serviceFeeConfig.value = config
        _serviceFeeKind.value = if (config.fixedEnabled) "fixed" else null
        Log.d("DirectCheckoutVM", "Config aplicada: allowOverride=${config.allowOverride}, fixedEnabled=${config.fixedEnabled}")
    }

    private fun reloadServiceFeeConfig(tokenOverride: String? = null) {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val token = tokenOverride ?: prefs.getString(Constants.TOKEN, null) ?: run {
            Log.w("DirectCheckoutVM", "Token não encontrado no SharedPreferences, usando apenas o cache.")
            return
        }
        viewModelScope.launch {
            try {
                val response = apiService.getTaxes("Bearer $token")
                val config = response.serviceFee
                if (config != null) {
                    ServiceFeeManager.saveConfig(context, config)
                    applyServiceFeeConfig(config)
                    calculateTotals()
                }
            } catch (e: Exception) {
                Log.e("DirectCheckoutVM", "Falha ao buscar service_fee da API: ${e.message}")
            }
        }
    }

    fun init(items: List<SaleViewModel.CartItem>, token: String? = null) {
        _cartItems.value = items
        calculateTotals()
        reloadServiceFeeConfig(token)
    }

    fun overrideServiceFee(kind: String, value: Double = 0.0) {
        _serviceFeeKind.value = kind
        _serviceFeeManualValue = value
        calculateTotals()
    }

    fun calculateTotals() {
        val items = _cartItems.value ?: emptyList()
        val base = items.sumOf { (it.product.selling_price ?: 0.0) * it.quantity }
        _baseTotal.value = base

        val taxes = _activeTaxes.value ?: emptyList()
        val currentCurrency = CurrencyManager.getInstance().selectedCurrency

        val taxPercentage = taxes.filter { it.currency.equals(currentCurrency, ignoreCase = true) }
            .sumOf { it.percentage }

        var tax = 0.0
        if (taxPercentage > 0) {
            tax = base * (taxPercentage / 100.0)
            _taxAmount.value = tax
        } else {
            _taxAmount.value = 0.0
        }

        var sfAmount = 0.0
        val config = _serviceFeeConfig.value
        val kind = _serviceFeeKind.value

        if (config != null && kind != null) {
            when (kind) {
                "fixed" -> {
                    val pct = _serviceFeeConfig.value?.fixedPercent ?: 0.0
                    sfAmount = base * (pct / 100.0)
                }
                "manual_percent" -> {
                    sfAmount = base * (_serviceFeeManualValue / 100.0)
                }
                "manual_value" -> {
                    sfAmount = _serviceFeeManualValue
                }
                "waived" -> {
                    sfAmount = 0.0
                }
            }
        }
        _serviceFeeAmount.value = sfAmount

        _finalTotal.value = base + tax + sfAmount
    }

    data class PreparedDirectSaleResult(
        val localId: String,
        val saleRequest: SaleRequest,
        val quote: SelectedPaymentQuote
    )

    fun restoreDurableRecovery() {
        viewModelScope.launch {
            val recovered = saleOutboxRepository.recoverApprovedWaitingSalesAtomic()
            val unresolved = saleOutboxRepository.getUnresolvedDirectPaymentState()
            _unresolvedPaymentState.value = unresolved
            _isPaymentBlocked.value = unresolved?.isBlocked ?: false
            _requiresReconciliation.value = unresolved?.requiresReconciliation ?: false
            _blockReason.value = unresolved?.blockReason
            if (recovered > 0) {
                Log.d("DirectCheckoutVM", "Recuperadas $recovered vendas diretas aprovadas do Room")
                saleSyncScheduler.scheduleSync(context)
            }
        }
    }

    suspend fun prepareDirectSaleOperation(
        quote: SelectedPaymentQuote,
        method: String,
        sessionId: String,
        operatorId: String?,
        operatorName: String?
    ): PreparedDirectSaleResult {
        val items = _cartItems.value?.map {
            SaleItem(it.product.id, it.product.name, it.quantity, it.product.selling_price ?: 0.0)
        } ?: emptyList()

        val saleRequest = SaleRequest(
            customerName = "Consumidor Final",
            total = quote.transactionAmount,
            items = items,
            paymentMethod = method,
            currency = quote.baseCurrency,
            paymentCurrency = quote.transactionCurrency,
            exchangeRatesSnapshot = quote.snapshot,
            caixa_session_id = sessionId,
            operatorId = operatorId,
            operatorName = operatorName,
            taxAmount = com.plugpdv.pdv.utils.MoneyDecimal.of(_taxAmount.value ?: 0.0),
            serviceFeeAmount = com.plugpdv.pdv.utils.MoneyDecimal.of(_serviceFeeAmount.value ?: 0.0),
            serviceFeeKind = _serviceFeeKind.value,
            convertedTotal = quote.baseAmount
        )

        val localId = UUID.randomUUID().toString()
        val minimalUnits = com.plugpdv.pdv.utils.MoneyDecimal.toMinorUnits(quote.transactionAmount, quote.transactionCurrency)

        saleOutboxRepository.prepareDirectSaleAtomic(
            saleRequest = saleRequest,
            currency = quote.baseCurrency,
            localId = localId,
            minimalUnitAmount = minimalUnits,
            orderId = localId,
            description = "Venda Direta - PDV"
        )
        Log.d("DirectCheckoutVM", "Venda direta K=$localId persistida atomicamente como WAITING_PAYMENT antes do PlugPay")

        return PreparedDirectSaleResult(localId, saleRequest, quote)
    }

    fun finalizeApprovedSale(
        operationId: String,
        paymentId: String?,
        method: String
    ) {
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val updated = saleOutboxRepository.finalizeApprovedSaleAtomic(operationId, paymentId, method)
                if (updated != null) {
                    Log.d("DirectCheckoutVM", "Venda direta K=$operationId promovida para PENDING após pagamento aprovado")
                    
                    val saleReq = runCatching { gson.fromJson(updated.payloadJson, SaleRequest::class.java) }.getOrNull()
                    if (saleReq != null) {
                        _latestReceiptSnapshot.value = ReceiptMoneySnapshot(
                            operationId = operationId,
                            transactionAmount = saleReq.total,
                            transactionCurrency = saleReq.paymentCurrency ?: saleReq.currency,
                            baseAmount = saleReq.convertedTotal ?: saleReq.total,
                            baseCurrency = saleReq.currency,
                            paymentMethod = method,
                            items = saleReq.items,
                            customerName = saleReq.customerName
                        )
                    }

                    saleSyncScheduler.scheduleSync(context)
                    val fakeResponse = SaleResponse(id = "LOCAL-$operationId", status = method)
                    _saleResult.value = SaleResult.Success(fakeResponse)
                    
                    _isPaymentBlocked.value = false
                    _requiresReconciliation.value = false
                    _blockReason.value = null
                    _unresolvedPaymentState.value = null
                } else {
                    Log.e("DirectCheckoutVM", "Venda direta K=$operationId não encontrada no Room")
                    _saleResult.value = SaleResult.Error("Venda não encontrada localmente: $operationId")
                }
            } catch (e: Exception) {
                Log.e("DirectCheckoutVM", "Erro ao finalizar venda aprovada K=$operationId: ${e.message}", e)
                _saleResult.value = SaleResult.Error("Falha ao registrar aprovação: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun finishCashSale(
        quote: SelectedPaymentQuote,
        sessionId: String,
        operatorId: String?,
        operatorName: String?
    ) {
        val items = _cartItems.value?.map {
            SaleItem(it.product.id, it.product.name, it.quantity, it.product.selling_price ?: 0.0)
        } ?: emptyList()

        val saleRequest = SaleRequest(
            customerName = "Consumidor Final",
            total = quote.transactionAmount,
            items = items,
            paymentMethod = "DINHEIRO",
            currency = quote.baseCurrency,
            paymentCurrency = quote.transactionCurrency,
            exchangeRatesSnapshot = quote.snapshot,
            caixa_session_id = sessionId,
            operatorId = operatorId,
            operatorName = operatorName,
            taxAmount = com.plugpdv.pdv.utils.MoneyDecimal.of(_taxAmount.value ?: 0.0),
            serviceFeeAmount = com.plugpdv.pdv.utils.MoneyDecimal.of(_serviceFeeAmount.value ?: 0.0),
            serviceFeeKind = _serviceFeeKind.value,
            convertedTotal = quote.baseAmount
        )

        val localId = UUID.randomUUID().toString()

        viewModelScope.launch {
            try {
                _isLoading.value = true
                saleOutboxRepository.enqueueSale(saleRequest, quote.baseCurrency, localId)
                Log.d("DirectCheckoutVM", "Venda dinheiro salva na Outbox com sucesso. localId: $localId")

                val fakeResponse = SaleResponse(id = "LOCAL-$localId", status = "DINHEIRO")
                _saleResult.value = SaleResult.Success(fakeResponse)
                saleSyncScheduler.scheduleSync(context)
            } catch (e: Exception) {
                Log.e("DirectCheckoutVM", "Erro fatal ao salvar venda dinheiro na Outbox: ${e.message}", e)
                _saleResult.value = SaleResult.Error("Falha crítica ao salvar venda localmente: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun finishSale(
        token: String,
        method: String,
        sessionId: String,
        operatorId: String?,
        operatorName: String?,
        manualAmount: Double? = null
    ) {
        val cm = CurrencyManager.getInstance()
        val baseCurrency = cm.getBaseCurrency()
        val txCurrency = cm.selectedCurrency
        val baseTotalAmount = manualAmount ?: (_finalTotal.value ?: 0.0)

        val quoteResult = cm.convertMoneyExact(
            amount = com.plugpdv.pdv.utils.MoneyDecimal.of(baseTotalAmount),
            fromCurrency = baseCurrency,
            toCurrency = txCurrency,
            baseCurrency = baseCurrency
        )

        if (quoteResult.isFailure) {
            Log.e("DirectCheckoutVM", "FX_RATE_MISSING: ${quoteResult.exceptionOrNull()?.message}")
            _saleResult.value = SaleResult.Error("FX_RATE_MISSING: Cotação de câmbio ausente")
            _isLoading.value = false
            return
        }

        val q = quoteResult.getOrThrow()
        val selectedQuote = SelectedPaymentQuote(
            transactionAmount = q.transactionAmount,
            transactionCurrency = q.transactionCurrency,
            baseAmount = q.baseAmount,
            baseCurrency = q.baseCurrency,
            fxRate = q.fxRate,
            snapshot = q.snapshot
        )

        if (method.equals("DINHEIRO", ignoreCase = true) || method.equals("CASH", ignoreCase = true)) {
            finishCashSale(selectedQuote, sessionId, operatorId, operatorName)
        } else {
            val items = _cartItems.value?.map {
                SaleItem(it.product.id, it.product.name, it.quantity, it.product.selling_price ?: 0.0)
            } ?: emptyList()

            val saleRequest = SaleRequest(
                customerName = "Consumidor Final",
                total = selectedQuote.transactionAmount,
                items = items,
                paymentMethod = method,
                currency = selectedQuote.baseCurrency,
                paymentCurrency = selectedQuote.transactionCurrency,
                exchangeRatesSnapshot = selectedQuote.snapshot,
                caixa_session_id = sessionId,
                operatorId = operatorId,
                operatorName = operatorName,
                taxAmount = com.plugpdv.pdv.utils.MoneyDecimal.of(_taxAmount.value ?: 0.0),
                serviceFeeAmount = com.plugpdv.pdv.utils.MoneyDecimal.of(_serviceFeeAmount.value ?: 0.0),
                serviceFeeKind = _serviceFeeKind.value,
                convertedTotal = selectedQuote.baseAmount
            )

            val localId = UUID.randomUUID().toString()

            viewModelScope.launch {
                try {
                    _isLoading.value = true
                    saleOutboxRepository.enqueueSale(saleRequest, selectedQuote.baseCurrency, localId)
                    Log.d("DirectCheckoutVM", "Venda salva na Outbox com sucesso. localId: $localId")
                    val fakeResponse = SaleResponse(id = "LOCAL-$localId", status = method)
                    _saleResult.value = SaleResult.Success(fakeResponse)
                    saleSyncScheduler.scheduleSync(context)
                } catch (e: Exception) {
                    Log.e("DirectCheckoutVM", "Erro fatal ao salvar venda localmente na Outbox: ${e.message}", e)
                    _saleResult.value = SaleResult.Error("Falha crítica ao salvar venda localmente: ${e.message}")
                } finally {
                    _isLoading.value = false
                }
            }
        }
    }
}

data class ReceiptMoneySnapshot(
    val operationId: String,
    val transactionAmount: java.math.BigDecimal,
    val transactionCurrency: String,
    val baseAmount: java.math.BigDecimal,
    val baseCurrency: String,
    val paymentMethod: String,
    val items: List<SaleItem>,
    val customerName: String?
)
