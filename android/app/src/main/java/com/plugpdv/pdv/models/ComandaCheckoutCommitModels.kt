package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName
import java.math.BigDecimal

data class CommandCheckoutCommitRequest(
    @SerializedName("action") val action: String = "checkout_commit",
    @SerializedName("comanda_id") val comandaId: String,
    @SerializedName("mesa_id") val mesaId: String? = null,
    @SerializedName("forma") val forma: String = "DINHEIRO",
    @SerializedName("valor") val valor: BigDecimal = BigDecimal.ZERO,
    @SerializedName("moeda") val moeda: String,
    @SerializedName("valor_base") val valorBase: BigDecimal? = null,
    @SerializedName("base_currency") val baseCurrency: String? = null,
    @SerializedName("fx_rate") val fxRate: BigDecimal? = null,
    @SerializedName("exchange_rates_snapshot") val exchangeRatesSnapshot: Map<String, String>? = null,
    @SerializedName("referencia_externa") val referenciaExterna: String? = null,
    @SerializedName("should_register_sale") val shouldRegisterSale: Boolean = false,
    @SerializedName("sale_items") val saleItems: List<SaleItem>? = null,
    @SerializedName("discount") val discount: BigDecimal = BigDecimal.ZERO,
    @SerializedName("service_fee") val serviceFee: BigDecimal = BigDecimal.ZERO,
    @SerializedName("service_fee_kind") val serviceFeeKind: String? = null
) {
    constructor(
        action: String = "checkout_commit",
        comandaId: String,
        mesaId: String? = null,
        forma: String = "DINHEIRO",
        valor: Double,
        moeda: String,
        valorBase: Double? = null,
        baseCurrency: String? = null,
        fxRate: Double? = null,
        exchangeRatesSnapshot: Map<String, String>? = null,
        referenciaExterna: String? = null,
        shouldRegisterSale: Boolean = false,
        saleItems: List<SaleItem>? = null,
        discount: Double = 0.0,
        serviceFee: Double = 0.0,
        serviceFeeKind: String? = null
    ) : this(
        action = action,
        comandaId = comandaId,
        mesaId = mesaId,
        forma = forma,
        valor = BigDecimal.valueOf(valor),
        moeda = moeda,
        valorBase = valorBase?.let { BigDecimal.valueOf(it) },
        baseCurrency = baseCurrency,
        fxRate = fxRate?.let { BigDecimal.valueOf(it) },
        exchangeRatesSnapshot = exchangeRatesSnapshot,
        referenciaExterna = referenciaExterna,
        shouldRegisterSale = shouldRegisterSale,
        saleItems = saleItems,
        discount = BigDecimal.valueOf(discount),
        serviceFee = BigDecimal.valueOf(serviceFee),
        serviceFeeKind = serviceFeeKind
    )
}

data class ComandaCheckoutCommitResponse(
    @SerializedName("success") val success: Boolean = true,
    @SerializedName("created_new") val createdNew: Boolean = true,
    @SerializedName("payment_id") val paymentId: String? = null,
    @SerializedName("sale_id") val saleId: String? = null,
    @SerializedName("comanda_id") val comandaId: String? = null,
    @SerializedName("comanda_status") val comandaStatus: String? = null,
    @SerializedName("mesa_id") val mesaId: String? = null,
    @SerializedName("mesa_status") val mesaStatus: String? = null,
    @SerializedName("base_currency") val baseCurrency: String? = null,
    @SerializedName("total_paid_base") val totalPaidBase: Double? = null,
    @SerializedName("total_paid") val totalPaid: Double = 0.0,
    @SerializedName("remaining_balance_base") val remainingBalanceBase: Double? = null,
    @SerializedName("remaining_balance") val remainingBalance: Double = 0.0,
    @SerializedName("closed") val closed: Boolean = false,
    @SerializedName("requires_reconciliation") val requiresReconciliation: Boolean = false
)
