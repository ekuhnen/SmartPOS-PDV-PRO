package com.plugpdv.pdv.utils

import android.content.Context
import com.google.gson.Gson
import com.plugpdv.pdv.models.ExchangeResponse
import com.plugpdv.pdv.models.CapabilitiesResponse
import java.text.NumberFormat
import java.util.*

class CurrencyManager private constructor() {
    private var rates: ExchangeResponse? = null
    private var ratesOwnerId: String? = null
    private var authorizedCurrencyCodes: List<String>? = null
    private var capabilitiesBaseCurrency: String? = null
    private var capabilitiesOwnerId: String? = null
    private var capabilitiesRequired: Boolean = false
    var selectedCurrency: String = "BRL"

    fun init(context: Context) {
        if (rates == null) {
            val prefs = context.getSharedPreferences("currency_prefs", Context.MODE_PRIVATE)
            val activeTenant = TenantBindingStore.getActiveTenantId(context)
            val cachedTenant = prefs.getString("currency_owner_id", null)
            if (activeTenant.isNullOrBlank() || cachedTenant != activeTenant) return
            ratesOwnerId = cachedTenant
            val cachedAllowed = prefs.getStringSet("authorized_currency_codes", null)
            authorizedCurrencyCodes = cachedAllowed?.map { it.uppercase() }?.sorted()
            capabilitiesRequired = true
            capabilitiesBaseCurrency = prefs.getString("authorized_base_currency", null)
            capabilitiesOwnerId = cachedTenant
            if (authorizedCurrencyCodes != null && capabilitiesBaseCurrency != null) {
                selectedCurrency = capabilitiesBaseCurrency!!
            }
            val json = prefs.getString("exchange_rates", null)
            if (!json.isNullOrEmpty()) {
                try {
                    val loaded = Gson().fromJson(json, ExchangeResponse::class.java)
                    setRatesInMemory(loaded, context, save = false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    /** Selects a tenant-scoped currency cache and drops another tenant's state. */
    fun prepareForTenant(context: Context, ownerId: String) {
        require(ownerId.isNotBlank())
        capabilitiesRequired = true
        if ((ratesOwnerId != null && ratesOwnerId != ownerId) ||
            (capabilitiesOwnerId != null && capabilitiesOwnerId != ownerId)) {
            rates = null
            ratesOwnerId = null
            authorizedCurrencyCodes = null
            capabilitiesBaseCurrency = null
            capabilitiesOwnerId = null
            capabilitiesRequired = true
            selectedCurrency = "BRL"
        }
        val prefs = context.getSharedPreferences("currency_prefs", Context.MODE_PRIVATE)
        val cachedOwner = prefs.getString("currency_owner_id", null)
        if (cachedOwner != ownerId) return
        ratesOwnerId = ownerId
        authorizedCurrencyCodes = prefs.getStringSet("authorized_currency_codes", null)
            ?.map { it.uppercase() }?.sorted()
        capabilitiesBaseCurrency = prefs.getString("authorized_base_currency", null)
        capabilitiesOwnerId = ownerId
        capabilitiesBaseCurrency?.let { selectedCurrency = it }
    }

    /** Capabilities are the authorization boundary; rates remain FX data only. */
    fun applyCapabilities(context: Context, ownerId: String, capabilities: CapabilitiesResponse) {
        require(ownerId.isNotBlank())
        val codes = capabilities.currencies.keys.map { it.uppercase() }.distinct()
        require(codes.isNotEmpty()) { "CURRENCY_CAPABILITIES_EMPTY" }
        prepareForTenant(context, ownerId)
        authorizedCurrencyCodes = codes
        capabilitiesOwnerId = ownerId
        capabilitiesBaseCurrency = capabilities.baseCurrency?.uppercase()?.takeIf { it in codes }
            ?: codes.firstOrNull { it.equals(getBaseCurrency(), true) }
            ?: codes.firstOrNull()
        val prefs = context.getSharedPreferences("currency_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("currency_owner_id", ownerId)
            .putStringSet("authorized_currency_codes", codes.toSet())
            .putString("authorized_base_currency", capabilitiesBaseCurrency)
            .commit()
        if (capabilitiesBaseCurrency != null) selectedCurrency = capabilitiesBaseCurrency!!

        // The login flow currently applies the terminal capabilities response
        // through CurrencyManager. Keep payment policy in its own tenant-scoped
        // store while preserving that single network fetch/call site.
        PaymentProviderCapabilitiesStore.getInstance().applyCapabilities(context, ownerId, capabilities)
    }

    fun getAuthorizedCurrencyCodes(): List<String> = authorizedCurrencyCodes.orEmpty()

    fun hasCapabilitiesAuthority(): Boolean = capabilitiesRequired

    fun isAuthorized(code: String): Boolean = authorizedCurrencyCodes?.contains(code.uppercase()) ?: false

    fun selectAuthorizedCurrency(code: String): Boolean {
        val normalized = code.trim().uppercase()
        if (capabilitiesRequired && normalized !in authorizedCurrencyCodes.orEmpty()) return false
        selectedCurrency = normalized
        return true
    }

    fun isOperational(code: String): Boolean = isAuthorized(code) &&
        (code.equals("BRL", true) || getRateForCurrencyExact(code) != null)

    fun setRates(context: Context, rates: ExchangeResponse?) {
        setRatesInMemory(rates, context, save = true)
    }

    fun setRates(rates: ExchangeResponse?) {
        setRatesInMemory(rates, null, save = false)
    }

    private fun setRatesInMemory(rates: ExchangeResponse?, context: Context?, save: Boolean) {
        this.rates = rates
        if (rates != null) {
            if (ratesOwnerId == null) ratesOwnerId = context?.let { TenantBindingStore.getActiveTenantId(it) }
            if (!rates.moeda_principal.isNullOrEmpty()) {
            if (selectedCurrency == "BRL" && rates.moeda_principal != "BRL") {
                selectedCurrency = rates.moeda_principal
            }
            }
        }
        if (save && context != null && rates != null) {
            try {
                val json = Gson().toJson(rates)
                context.getSharedPreferences("currency_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("exchange_rates", json)
                    .putString("currency_owner_id", ratesOwnerId)
                    .apply()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun getBaseCurrency(): String {
        return capabilitiesBaseCurrency ?: rates?.moeda_principal ?: "BRL"
    }

    fun getAvailableCurrencies(): List<ExchangeResponse.CurrencyRate> {
        val current = rates?.moedas.orEmpty()
        val allowed = authorizedCurrencyCodes ?: return if (capabilitiesRequired) emptyList() else current
        return allowed.map { code -> current.firstOrNull { it.codigo.equals(code, true) }
            ?: ExchangeResponse.CurrencyRate(code, 0.0) }
    }

    /**
     * Retorna a taxa relativa ao BRL para uma moeda como BigDecimal exato.
     * Retorna null se não houver taxa registrada (fail-closed).
     */
    fun getRateForCurrencyExact(code: String): java.math.BigDecimal? {
        val upper = code.uppercase()
        if (upper == "BRL") return java.math.BigDecimal.ONE
        val currentRates = rates?.moedas ?: return null
        for (rate in currentRates) {
            if (rate.codigo.equals(upper, ignoreCase = true)) {
                return if (rate.taxa != 0.0) java.math.BigDecimal.valueOf(rate.taxa) else null
            }
        }
        return null
    }

    /**
     * Normaliza todas as cotações para a moeda-base especificada (Option A).
     * Retorna Result com mapa determinístico ou falha se a taxa da base for ausente.
     */
    fun getNormalizedSnapshotForBase(baseCurrency: String): Result<Map<String, String>> {
        val upperBase = baseCurrency.uppercase()
        val result = mutableMapOf<String, String>()
        val baseRateToBrl = getRateForCurrencyExact(upperBase)
            ?: return Result.failure(IllegalStateException("FX_RATE_MISSING: No rate for base currency $upperBase"))

        result[upperBase] = "1"
        if (upperBase != "BRL") {
            val brlPerBase = java.math.BigDecimal.ONE.divide(baseRateToBrl, 6, java.math.RoundingMode.HALF_UP)
            result["BRL"] = brlPerBase.stripTrailingZeros().toPlainString()
        }

        rates?.moedas?.forEach { r ->
            val code = r.codigo.uppercase()
            if (code != upperBase && r.taxa > 0.0) {
                val rRateToBrl = java.math.BigDecimal.valueOf(r.taxa)
                val ratePerBase = rRateToBrl.divide(baseRateToBrl, 6, java.math.RoundingMode.HALF_UP)
                result[code] = ratePerBase.stripTrailingZeros().toPlainString()
            }
        }
        return Result.success(result)
    }

    /**
     * Gera cotação monetária oficial e determinística para uma transação (MoneyQuote).
     * Contrato: fx_rate = unidades de transactionCurrency por 1 unidade de baseCurrency.
     * base_amount = transaction_amount / fx_rate.
     */
    fun quoteTransactionAmount(
        transactionAmount: java.math.BigDecimal,
        transactionCurrency: String,
        baseCurrency: String
    ): Result<MoneyQuote> {
        val txUpper = transactionCurrency.uppercase()
        val baseUpper = baseCurrency.uppercase()

        val snapshotResult = getNormalizedSnapshotForBase(baseUpper)
        if (snapshotResult.isFailure) {
            return Result.failure(snapshotResult.exceptionOrNull() ?: IllegalStateException("FX_RATE_MISSING"))
        }
        val snapshot = snapshotResult.getOrThrow()

        if (txUpper == baseUpper) {
            val roundedTx = MoneyDecimal.roundToCurrency(transactionAmount, txUpper)
            val roundedBase = MoneyDecimal.roundToCurrency(transactionAmount, baseUpper)
            return Result.success(
                MoneyQuote(
                    transactionAmount = roundedTx,
                    transactionCurrency = txUpper,
                    baseAmount = roundedBase,
                    baseCurrency = baseUpper,
                    fxRate = java.math.BigDecimal.ONE,
                    snapshot = snapshot
                )
            )
        }

        val rateBaseBrl = getRateForCurrencyExact(baseUpper)
            ?: return Result.failure(IllegalStateException("FX_RATE_MISSING: Missing exchange rate for base currency $baseUpper"))
        val rateTxBrl = getRateForCurrencyExact(txUpper)
            ?: return Result.failure(IllegalStateException("FX_RATE_MISSING: Missing exchange rate for transaction currency $txUpper"))

        // fx_rate = tx_per_BRL / base_per_BRL = unidades de txCurrency por 1 unidade de baseCurrency
        val fxRate = rateTxBrl.divide(rateBaseBrl, 8, java.math.RoundingMode.HALF_UP)
        val calculatedBase = transactionAmount.divide(fxRate, 8, java.math.RoundingMode.HALF_UP)

        val quote = MoneyQuote(
            transactionAmount = MoneyDecimal.roundToCurrency(transactionAmount, txUpper),
            transactionCurrency = txUpper,
            baseAmount = MoneyDecimal.roundToCurrency(calculatedBase, baseUpper),
            baseCurrency = baseUpper,
            fxRate = fxRate.stripTrailingZeros(),
            snapshot = snapshot
        )
        return Result.success(quote)
    }

    /**
     * Quotes an amount that is already expressed in the authoritative base currency
     * for display/payment in a transaction currency.
     */
    fun quoteBaseAmount(
        baseAmount: java.math.BigDecimal,
        baseCurrency: String,
        transactionCurrency: String
    ): Result<MoneyQuote> = convertMoneyExact(
        amount = baseAmount,
        fromCurrency = baseCurrency,
        toCurrency = transactionCurrency,
        baseCurrency = baseCurrency
    )

    /**
     * Converte valor financeiro com precisão estrita BigDecimal, fail-closed e normalização.
     */
    fun convertMoneyExact(
        amount: java.math.BigDecimal,
        fromCurrency: String,
        toCurrency: String,
        baseCurrency: String? = null
    ): Result<MoneyQuote> {
        val fromUpper = fromCurrency.uppercase()
        val toUpper = toCurrency.uppercase()
        val targetBase = (baseCurrency ?: getBaseCurrency()).uppercase()

        if (fromUpper == targetBase) {
            // amount está na base -> converte para toCurrency (moeda da transação)
            val rateBaseBrl = getRateForCurrencyExact(targetBase)
                ?: return Result.failure(IllegalStateException("FX_RATE_MISSING: No rate for $targetBase"))
            val rateTxBrl = getRateForCurrencyExact(toUpper)
                ?: return Result.failure(IllegalStateException("FX_RATE_MISSING: No rate for $toUpper"))

            val fxRate = if (fromUpper == toUpper) java.math.BigDecimal.ONE else rateTxBrl.divide(rateBaseBrl, 8, java.math.RoundingMode.HALF_UP)
            val txAmount = amount.multiply(fxRate)
            val snapshot = getNormalizedSnapshotForBase(targetBase).getOrElse { return Result.failure(it) }

            return Result.success(
                MoneyQuote(
                    transactionAmount = MoneyDecimal.roundToCurrency(txAmount, toUpper),
                    transactionCurrency = toUpper,
                    baseAmount = MoneyDecimal.roundToCurrency(amount, targetBase),
                    baseCurrency = targetBase,
                    fxRate = fxRate.stripTrailingZeros(),
                    snapshot = snapshot
                )
            )
        }

        // amount está em fromCurrency -> cita na moeda de transação fromCurrency para a base targetBase
        return quoteTransactionAmount(amount, fromUpper, targetBase)
    }

    /**
     * Retorna a taxa relativa ao BRL para uma moeda.
     * Na API de câmbio, a base de todas as cotações é "BRL".
     */
    fun getRateForCurrency(code: String): Double {
        if (code.equals("BRL", ignoreCase = true)) return 1.0
        val currentRates = rates?.moedas ?: return 1.0
        for (rate in currentRates) {
            if (rate.codigo.equals(code, ignoreCase = true)) {
                return if (rate.taxa != 0.0) rate.taxa else 1.0
            }
        }
        return 1.0
    }

    /**
     * Converte QUALQUER valor da moeda 'fromCurrency' para BRL.
     */
    fun toBrl(amount: Double, fromCurrency: String): Double {
        if (fromCurrency.equals("BRL", ignoreCase = true)) return amount
        val rate = getRateForCurrency(fromCurrency)
        return amount / rate
    }

    /**
     * Converte QUALQUER valor em BRL para a moeda 'toCurrency'.
     */
    fun fromBrl(amountInBrl: Double, toCurrency: String): Double {
        if (toCurrency.equals("BRL", ignoreCase = true)) return amountInBrl
        val rate = getRateForCurrency(toCurrency)
        return amountInBrl * rate
    }

    /**
     * Converte um valor em BRL para a moeda atualmente selecionada no app.
     */
    fun convert(valueInBrl: Double): Double {
        return fromBrl(valueInBrl, selectedCurrency)
    }

    fun convertToBrl(valueInSelectedCurrency: Double): Double {
        return toBrl(valueInSelectedCurrency, selectedCurrency)
    }

    fun convertCurrencyToBase(value: Double, fromCurrency: String): Double {
        return toBrl(value, fromCurrency)
    }

    @Deprecated("Input must be BRL; use explicit conversion and formatExplicit for financial values")
    fun format(valueInBrl: Double): String {
        return formatExplicit(convert(valueInBrl), selectedCurrency)
    }

    private val defaultRulesProvider = DefaultCurrencyRulesProvider()

    fun formatExplicit(value: Double, currencyCode: String): String {
        return defaultRulesProvider.formatExplicit(value, currencyCode)
    }

    fun formatMinorUnits(amountMinorUnits: Long, currencyCode: String): String {
        return defaultRulesProvider.formatMinorUnits(amountMinorUnits, currencyCode)
    }

    companion object {
        @Volatile
        private var instance: CurrencyManager? = null

        @JvmStatic
        fun getInstance(): CurrencyManager {
            return instance ?: synchronized(this) {
                instance ?: CurrencyManager().also { instance = it }
            }
        }
    }
}
