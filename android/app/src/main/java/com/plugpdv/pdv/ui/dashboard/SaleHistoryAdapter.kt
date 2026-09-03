package com.plugpdv.pdv.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.DashboardHistoryItem
import com.plugpdv.pdv.utils.CurrencyManager

class SaleHistoryAdapter(private var items: List<DashboardHistoryItem> = emptyList()) : RecyclerView.Adapter<SaleHistoryAdapter.ViewHolder>() {
    fun updateData(newItems: List<DashboardHistoryItem>) { items = newItems; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_sale_history, parent, false))
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.id.text = item.id.ifBlank { holder.itemView.context.getString(R.string.sale_number, position + 1) }
        holder.time.text = item.createdAt ?: "--"
        holder.total.text = CurrencyManager.getInstance().formatMinorUnits(item.money.amountMinor, item.money.currency)
        holder.method.text = listOfNotNull(item.paymentMethod, item.operator).joinToString(" • ").ifBlank { item.money.currency }
    }
    override fun getItemCount() = items.size
    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val id: TextView = view.findViewById(R.id.tvSaleId)
        val time: TextView = view.findViewById(R.id.tvSaleTime)
        val total: TextView = view.findViewById(R.id.tvSaleTotal)
        val method: TextView = view.findViewById(R.id.tvSaleMethod)
    }
}
