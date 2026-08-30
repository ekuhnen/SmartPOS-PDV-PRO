package com.plugpdv.pdv.ui.sale

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.TableItem
import com.plugpdv.pdv.utils.CurrencyManager

class TableOrderItemAdapter(
    private var items: List<TableItem>,
    private val listener: (TableItem) -> Unit
) : RecyclerView.Adapter<TableOrderItemAdapter.ViewHolder>() {

    fun setItems(newItems: List<TableItem>) {
        this.items = newItems
        notifyDataSetChanged()
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
        private val ivPaidIndicator: android.widget.ImageView = itemView.findViewById(R.id.ivPaidIndicator)

        fun bind(item: TableItem, listener: (TableItem) -> Unit) {
            tvName.text = item.product.name ?: "Sem Nome"
            val price = item.product.selling_price
            tvPrice.text = if (price != null) CurrencyManager.getInstance().format(price * item.quantity.toDouble()) else "UNKNOWN"
            
            if (item.observation.isNullOrEmpty()) {
                tvObservation.visibility = View.GONE
            } else {
                tvObservation.visibility = View.VISIBLE
                tvObservation.text = item.observation
            }

            if (item.removed) {
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "REMOVIDO: ${item.removalReason}"
                tvName.alpha = 0.5f
                tvQuantity.alpha = 0.5f
                tvPrice.alpha = 0.5f
                tvPrice.paintFlags = tvPrice.paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                tvStatus.visibility = View.GONE
                tvName.alpha = 1.0f
                tvQuantity.alpha = 1.0f
                tvPrice.alpha = 1.0f
                tvPrice.paintFlags = tvPrice.paintFlags and android.graphics.Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }

            val isFullyPaid = item.isPaid || item.paidQuantity >= item.quantity
            ivPaidIndicator.visibility = if (isFullyPaid) View.VISIBLE else View.GONE

            if (item.removed || isFullyPaid) {
                itemView.setOnClickListener(null)
            } else {
                itemView.setOnClickListener { listener(item) }
            }
        }
    }
}
