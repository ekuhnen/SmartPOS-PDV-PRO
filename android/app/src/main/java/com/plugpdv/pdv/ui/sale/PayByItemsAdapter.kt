package com.plugpdv.pdv.ui.sale

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.R
import com.plugpdv.pdv.databinding.ItemCheckoutSplitBinding
import com.plugpdv.pdv.models.TableItemPayment
import com.plugpdv.pdv.models.PaymentQuoteResponse
import com.plugpdv.pdv.utils.CurrencyManager
import java.math.BigDecimal

/** Shared renderer for production and debug pay-by-items validation. */
class PayByItemsAdapter(
    private val items: List<TableItemPayment>,
    private val onSelect: (Int, Boolean) -> Unit,
    private val onQuantityChanged: (Int, Int) -> Unit,
    private val quotes: Map<String, PaymentQuoteResponse> = emptyMap()
) : RecyclerView.Adapter<PayByItemsAdapter.ViewHolder>() {
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder =
        ViewHolder(ItemCheckoutSplitBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val tip = items[position]
        val remaining = (tip.item.quantity - tip.item.paidQuantity).coerceAtLeast(0)
        val selectedQty = if (tip.selected) tip.selectedQuantity.coerceIn(0, remaining) else 0
        tip.selectedQuantity = selectedQty
        holder.binding.cbItemSelected.isChecked = tip.selected
        holder.binding.tvSelectedQuantity.text = selectedQty.toString()
        holder.binding.btnDecreaseQuantity.isEnabled = selectedQty > 0
        holder.binding.btnIncreaseQuantity.isEnabled = selectedQty < remaining
        // The dedicated quantity control is the sole quantity authority in
        // ITEMS mode; repeating it in the product title is redundant and
        // caused the physical row to read as two quantities.
        holder.binding.tvItemName.text = tip.item.product.name
            ?: holder.itemView.context.getString(R.string.unnamed_product)
        val price = tip.item.product.selling_price
        val currency = tip.item.product.price_currency
        holder.binding.tvItemValue.text = format(price, currency, 1)
        val quote = tip.item.id?.let { quotes["$it|${selectedQty.coerceAtLeast(1)}|${CurrencyManager.getInstance().selectedCurrency}"] }
        holder.binding.tvItemSubtotal.text = quote?.items?.firstOrNull()?.coverageAmount?.let {
            CurrencyManager.getInstance().formatExplicit(it, quote.currency ?: currency ?: CurrencyManager.getInstance().selectedCurrency)
        } ?: if (selectedQty > 0) "…" else "—"
        holder.itemView.setOnClickListener { onSelect(position, !tip.selected) }
        holder.binding.cbItemSelected.setOnClickListener { onSelect(position, holder.binding.cbItemSelected.isChecked) }
        holder.binding.btnDecreaseQuantity.setOnClickListener { onQuantityChanged(position, -1) }
        holder.binding.btnIncreaseQuantity.setOnClickListener { onQuantityChanged(position, 1) }
    }

    private fun format(price: Double?, currency: String?, quantity: Int): String {
        if (price == null || currency.isNullOrBlank()) return "UNKNOWN"
        val cm = CurrencyManager.getInstance()
        val amount = BigDecimal.valueOf(price).multiply(BigDecimal.valueOf(quantity.toLong()))
        val target = cm.selectedCurrency
        val quote = cm.quoteBaseAmount(amount, currency, target).getOrNull()
        return if (quote != null) cm.formatExplicit(quote.transactionAmount.toDouble(), quote.transactionCurrency)
        else cm.formatExplicit(amount.toDouble(), currency)
    }

    override fun getItemCount(): Int = items.size
    class ViewHolder(val binding: ItemCheckoutSplitBinding) : RecyclerView.ViewHolder(binding.root)
}
