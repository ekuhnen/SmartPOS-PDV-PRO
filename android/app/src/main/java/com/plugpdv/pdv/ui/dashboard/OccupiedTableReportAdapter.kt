package com.plugpdv.pdv.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.TableReportItem
import com.plugpdv.pdv.utils.CurrencyManager

class OccupiedTableReportAdapter(
    private var items: List<TableReportItem> = emptyList()
) : RecyclerView.Adapter<OccupiedTableReportAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvTableNumber: TextView = view.findViewById(R.id.tvTableNumber)
        val tvCustomerName: TextView = view.findViewById(R.id.tvCustomerName)
        val tvPendingAmount: TextView = view.findViewById(R.id.tvPendingAmount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_occupied_table_report, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvTableNumber.text = "Mesa ${item.number}"
        holder.tvCustomerName.text = if (!item.customerName.isNullOrEmpty()) "Cliente: ${item.customerName}" else "Sem nome de cliente"
        holder.tvPendingAmount.text = CurrencyManager.getInstance().format(item.pendingAmountBrl)
    }

    override fun getItemCount(): Int = items.size

    fun updateData(newItems: List<TableReportItem>) {
        items = newItems
        notifyDataSetChanged()
    }
}
