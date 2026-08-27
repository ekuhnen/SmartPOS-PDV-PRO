package com.plugpdv.pdv.ui.sale

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.TaxEntity
import com.plugpdv.pdv.models.SaleItem
import com.plugpdv.pdv.models.SaleRequest
import com.plugpdv.pdv.models.SaleResponse
import com.plugpdv.pdv.models.ServiceFeeConfig
import com.plugpdv.pdv.outbox.SaleSyncScheduler
import com.plugpdv.pdv.repository.SaleOutboxRepository
import com.plugpdv.pdv.repository.TaxRepository
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.CurrencyManager
import com.plugpdv.pdv.utils.ServiceFeeManager
import dagger.hilt.android.lifecycle.HiltViewModel
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

    init {
        val cached = ServiceFeeManager.getConfig(context)
        Log.d("DirectCheckoutVM", "ServiceFeeConfig from cache: $cached")
        if (cached != null) {
            applyServiceFeeConfig(cached)
        }

        taxRepository.getActiveTaxesLiveData().observeForever {
            _activeTaxes.value = it ?: emptyList()
            calculateTotals()
        }
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

    fun init(items: List<SaleViewModel.CartItem>, tokenOverride: String? = null) {
        _cartItems.value = items
        calculateTotals()
        reloadServiceFeeConfig(tokenOverride)
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
        val kind = _serviceFeeKind.value
        if (kind != null) {
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

    fun finishSale(
        token: String,
        method: String,
        sessionId: String,
        operatorId: String?,
        operatorName: String?,
        manualAmount: Double? = null
    ) {
        val items = _cartItems.value?.map {
            SaleItem(it.product.id, it.product.name, it.quantity, it.product.selling_price ?: 0.0)
        } ?: emptyList()

        val finalToPay = manualAmount ?: (_finalTotal.value ?: 0.0)
        val currency = CurrencyManager.getInstance().selectedCurrency

        val saleRequest = SaleRequest(
            customerName = "Consumidor Final",
            total = finalToPay,
            items = items,
            paymentMethod = method,
            currency = currency,
            caixa_session_id = sessionId,
            operatorId = operatorId,
            operatorName = operatorName,
            taxAmount = if (manualAmount != null) 0.0 else (_taxAmount.value ?: 0.0),
            serviceFeeAmount = if (manualAmount != null) 0.0 else (_serviceFeeAmount.value ?: 0.0),
            serviceFeeKind = if (manualAmount != null) null else _serviceFeeKind.value
        )

        val localId = UUID.randomUUID().toString()

        viewModelScope.launch {
            try {
                _isLoading.value = true

                // 1. Salvar snapshot imutável da venda na Outbox (Room) ANTES de liberar a UI
                saleOutboxRepository.enqueueSale(saleRequest, currency, localId)
                Log.d("DirectCheckoutViewModel", "Venda salva na Outbox com sucesso. localId: $localId")

                // 2. Libera a tela IMEDIATAMENTE ("Piscou, imprimiu") apenas após confirmação do salvamento local
                val fakeResponse = SaleResponse(id = "LOCAL-$localId", status = method)
                _saleResult.value = SaleResult.Success(fakeResponse)

                // 3. Agenda sincronização via WorkManager (durável, retoma se o app fechar/reiniciar)
                saleSyncScheduler.scheduleSync(context)

            } catch (e: Exception) {
                Log.e("DirectCheckoutViewModel", "Erro fatal ao salvar venda localmente na Outbox: ${e.message}")
                e.printStackTrace()
                // Requisito 15: se o Room não conseguir persistir localmente, exibe erro e permite nova tentativa
                _saleResult.value = SaleResult.Error("Falha crítica ao salvar venda localmente: ${e.message}")
            } finally {
                _isLoading.value = false
            }
        }
    }
}
