package com.plugpdv.pdv.ui.sale

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.DiffUtil
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.TableItem
import com.plugpdv.pdv.utils.CurrencyManager
import java.math.BigDecimal

class TableOrderItemAdapter(
    private var items: List<TableItem>,
    private val listener: (TableItem) -> Unit
) : RecyclerView.Adapter<TableOrderItemAdapter.ViewHolder>() {

    fun setItems(newItems: List<TableItem>) {
        val oldItems = items
        this.items = newItems
        DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = oldItems.size
            override fun getNewListSize() = newItems.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition].id == newItems[newItemPosition].id &&
                    oldItems[oldItemPosition].product.id == newItems[newItemPosition].product.id
            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldItems[oldItemPosition] == newItems[newItemPosition]
        }).dispatchUpdatesTo(this)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_table_order_item, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.bind(item, listener)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvProductName)
        private val tvQuantity: TextView = itemView.findViewById(R.id.tvQuantity)
        private val tvPrice: TextView = itemView.findViewById(R.id.tvPrice)
        private val tvObservation: TextView = itemView.findViewById(R.id.tvObservation)
        private val tvStatus: TextView = itemView.findViewById(R.id.tvStatus)
        private val btnPriceDetails: android.widget.ImageButton = itemView.findViewById(R.id.btnPriceDetails)
        private val ivPaidIndicator: android.widget.ImageView = itemView.findViewById(R.id.ivPaidIndicator)

        fun bind(item: TableItem, listener: (TableItem) -> Unit) {
            tvName.text = item.product.name ?: itemView.context.getString(R.string.unnamed_product)
            tvQuantity.text = itemView.context.getString(R.string.quantity_short, item.quantity)
            val price = item.product.selling_price
            val currency = item.product.price_currency
            tvPrice.text = if (price != null && !currency.isNullOrBlank()) {
                formatAmount(price * item.quantity.toDouble(), currency)
            } else "UNKNOWN"
            btnPriceDetails.visibility = if (price != null && !currency.isNullOrBlank() &&
                !currency.equals(CurrencyManager.getInstance().selectedCurrency, ignoreCase = true)) View.VISIBLE else View.GONE
            btnPriceDetails.setOnClickListener { showPriceDetails(item, price, currency) }
            
            if (item.observation.isNullOrEmpty()) {
                tvObservation.visibility = View.GONE
            } else {
                tvObservation.visibility = View.VISIBLE
                tvObservation.text = item.observation
            }

            val isFullyPaid = item.isPaid || item.paidQuantity >= item.quantity
            if (item.removed) {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = itemView.context.getString(R.string.removed_with_reason, item.removalReason)
                tvName.alpha = 0.5f
                tvQuantity.alpha = 0.5f
                tvPrice.alpha = 0.5f
                tvPrice.paintFlags = tvPrice.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            } else if (isFullyPaid) {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = itemView.context.getString(R.string.paid_status)
                tvName.alpha = 0.7f
                tvQuantity.alpha = 0.7f
                tvPrice.alpha = 0.7f
                tvPrice.paintFlags = tvPrice.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            } else if (item.paidQuantity > 0) {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = itemView.context.getString(R.string.paid_quantity_status, item.paidQuantity, item.quantity)
                tvName.alpha = 1.0f
                tvQuantity.alpha = 1.0f
                tvPrice.alpha = 1.0f
                tvPrice.paintFlags = tvPrice.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            } else {
                tvStatus.visibility = View.GONE
                tvName.alpha = 1.0f
                tvQuantity.alpha = 1.0f
                tvPrice.alpha = 1.0f
                tvPrice.paintFlags = tvPrice.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            ivPaidIndicator.visibility = if (isFullyPaid) View.VISIBLE else View.GONE

            if (item.removed || isFullyPaid) {
                itemView.setOnClickListener(null)
            } else {
                itemView.setOnClickListener { listener(item) }
            }
        }

        private fun formatAmount(amount: Double, sourceCurrency: String): String {
            val cm = CurrencyManager.getInstance()
            val selected = cm.selectedCurrency
            if (sourceCurrency.equals(selected, ignoreCase = true)) {
                return cm.formatExplicit(amount, sourceCurrency)
            }
            val quote = cm.quoteBaseAmount(BigDecimal.valueOf(amount), sourceCurrency, selected).getOrNull()
            return if (quote != null) {
                cm.formatExplicit(quote.transactionAmount.toDouble(), quote.transactionCurrency)
            } else {
                cm.formatExplicit(amount, sourceCurrency)
            }
        }

        private fun showPriceDetails(item: TableItem, price: Double?, sourceCurrency: String?) {
            if (price == null || sourceCurrency.isNullOrBlank()) return
            val cm = CurrencyManager.getInstance()
            val selected = cm.selectedCurrency
            val unitBase = cm.formatExplicit(price, sourceCurrency)
            val baseSubtotal = cm.formatExplicit(price * item.quantity, sourceCurrency)
            val message = if (sourceCurrency.equals(selected, ignoreCase = true)) {
                itemView.context.getString(R.string.base_price) + ": " + unitBase + "\n" +
                    itemView.context.getString(R.string.transaction_currency) + ": " + selected + "\n" +
                    itemView.context.getString(R.string.no_exchange_applied)
            } else {
                val quote = cm.quoteBaseAmount(BigDecimal.valueOf(price), sourceCurrency, selected).getOrNull()
                val subtotalQuote = cm.quoteBaseAmount(BigDecimal.valueOf(price * item.quantity), sourceCurrency, selected).getOrNull()
                if (quote == null || subtotalQuote == null) return
                itemView.context.getString(R.string.base_price) + ": " + unitBase + "\n" +
                    itemView.context.getString(R.string.calculation_unit_price_label) + ": " + unitBase + "\n" +
                    itemView.context.getString(R.string.quantity_label) + ": " + item.quantity + "\n" +
                    itemView.context.getString(R.string.base_subtotal) + ": " + baseSubtotal + "\n" +
                    itemView.context.getString(R.string.applied_rate) + ": 1 $sourceCurrency = " + cm.formatExplicit(quote.fxRate.toDouble(), selected) + "\n" +
                    itemView.context.getString(R.string.converted_subtotal) + ": " + cm.formatExplicit(subtotalQuote.transactionAmount.toDouble(), selected)
            }
            AlertDialog.Builder(itemView.context)
                .setTitle(item.product.name ?: itemView.context.getString(R.string.product))
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
