package com.plugpdv.pdv.models

import com.google.gson.annotations.SerializedName
import com.google.gson.JsonElement
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
    @SerializedName("service_fee_kind") val serviceFeeKind: String? = null,
    @SerializedName("items") val items: List<ComandaItemAllocation>? = null
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
        serviceFeeKind: String? = null,
        items: List<ComandaItemAllocation>? = null
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
        serviceFeeKind = serviceFeeKind,
        items = items
    )
}

data class ComandaItemAllocation(
    @SerializedName("comanda_item_id") val comandaItemId: String,
    val quantity: Int
)

data class PaymentQuoteRequest(
    @SerializedName("action") val action: String = "payment_quote",
    @SerializedName("comanda_id") val comandaId: String,
    @SerializedName("items") val items: List<ComandaItemAllocation>,
    @SerializedName("forma") val forma: String = "DINHEIRO",
    @SerializedName("moeda") val moeda: String? = null
)

data class PaymentQuoteLine(
    @SerializedName("comanda_item_id") val comandaItemId: String? = null,
    @SerializedName("nome_snapshot") val name: String? = null,
    @SerializedName("paid_quantity") val paidQuantity: Int = 0,
    @SerializedName("unit_price") val unitPrice: Double? = null,
    @SerializedName("item_subtotal") val itemSubtotal: Double? = null,
    @SerializedName("allocated_discount") val allocatedDiscount: Double? = null,
    @SerializedName("allocated_tax") val allocatedTax: Double? = null,
    @SerializedName("allocated_service") val allocatedService: Double? = null,
    @SerializedName("coverage_amount") val coverageAmount: Double? = null,
    val currency: String? = null
)

data class PaymentQuoteResponse(
    @SerializedName("comanda_id") val comandaId: String? = null,
    val currency: String? = null,
    val items: List<PaymentQuoteLine> = emptyList(),
    @SerializedName("item_subtotal") val itemSubtotal: Double? = null,
    @SerializedName("allocated_discount") val allocatedDiscount: Double? = null,
    @SerializedName("allocated_tax") val allocatedTax: Double? = null,
    @SerializedName("allocated_service") val allocatedService: Double? = null,
    @SerializedName("accounting_total") val accountingTotal: Double? = null,
    @SerializedName("transaction_currency") val transactionCurrency: String? = null,
    @SerializedName("transaction_amount") val transactionAmount: Double? = null,
    @SerializedName("cash_tender") val cashTender: Double? = null,
    @SerializedName("cash_rounding_diff") val cashRoundingDiff: Double? = null,
    @SerializedName("fx_rate") val fxRate: Double? = null,
    @SerializedName("fx_source") val fxSource: String? = null
)

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

data class SetComandaServiceFeeResponse(
    val ok: Boolean = false,
    @SerializedName("comanda_id") val comandaId: String? = null,
    val currency: String? = null,
    @SerializedName("service_fee_mode") val serviceFeeMode: String? = null,
    @SerializedName("service_fee_percent") val serviceFeePercent: Double? = null,
    @SerializedName("service_fee_percent_base") val serviceFeePercentBase: Double? = null,
    val subtotal: Double? = null,
    @SerializedName("total_descontos") val totalDescontos: Double? = null,
    @SerializedName("tax_amount") val taxAmount: Double? = null,
    @SerializedName("tax_snapshot") val taxSnapshot: JsonElement? = null,
    @SerializedName("service_fee") val serviceFee: Double = 0.0,
    @SerializedName("total_liquido") val totalLiquido: Double? = null,
    @SerializedName("total_pago_base") val totalPagoBase: Double? = null,
    @SerializedName("saldo_base") val saldoBase: Double? = null,
    val versao: Long? = null
)
