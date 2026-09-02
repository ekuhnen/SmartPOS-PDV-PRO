package com.plugpdv.pdv.ui.sale

import android.content.Context
import com.plugpdv.pdv.R

/** Text-only renderer. All amounts and selections are supplied by CheckoutPrintModel. */
object CheckoutReceiptRenderer {
    fun render(context: Context, model: CheckoutPrintModel, formatAmount: (Double) -> String): String {
        val b = StringBuilder()
        b.append(context.getString(R.string.print_table_label)).append(" ").append(model.tableNumber).append("\n")
        b.append("--------------------------------\n")
        b.append(context.getString(if (model.mode == 2) R.string.print_charge_items_label else R.string.print_items_label)).append("\n")
        model.lines.forEach { line ->
            val name = line.item.product.name ?: context.getString(R.string.unnamed_product)
            val amount = line.subtotalDisplay.ifBlank { formatAmount(model.subtotal) }
            b.append(name).append(" x").append(line.selectedQuantity).append(" ").append(amount).append("\n")
        }
        if (model.mode == 1) b.append(context.getString(R.string.print_split_label)).append("\n")
        b.append(context.getString(R.string.subtotal)).append(": ").append(formatAmount(model.subtotal)).append("\n")
        if (model.tax > 0.0) b.append(context.getString(R.string.print_tax_label)).append(": ").append(formatAmount(model.tax)).append("\n")
        if (model.serviceFee > 0.0) b.append(context.getString(R.string.print_service_fee_label)).append(" ").append(formatAmount(model.serviceFee)).append("\n")
        b.append(context.getString(R.string.total_to_pay_label).uppercase()).append(": ").append(formatAmount(model.finalToPay)).append("\n")
        b.append("--------------------------------\n")
        b.append(context.getString(R.string.comanda_total)).append(": ").append(formatAmount(model.comandaTotal)).append("\n")
        b.append(context.getString(R.string.print_paid_amount_label)).append(" ").append(formatAmount(model.paid)).append("\n")
        b.append(context.getString(R.string.remaining_balance)).append(": ").append(formatAmount(model.balance)).append("\n")
        return b.toString()
    }
}
