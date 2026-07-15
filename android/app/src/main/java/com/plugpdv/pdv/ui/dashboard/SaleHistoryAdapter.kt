package com.plugpdv.pdv.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.SaleHistoryItem
import com.plugpdv.pdv.utils.CurrencyManager

class SaleHistoryAdapter(private var items: MutableList<SaleHistoryItem>) : RecyclerView.Adapter<SaleHistoryAdapter.ViewHolder>() {

    fun updateData(newItems: List<SaleHistoryItem>) {
        this.items = newItems.toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sale_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        
        val time = if (item.createdAt != null && item.createdAt.length >= 16) item.createdAt.substring(11, 16) else "--:--"
        val method = item.paymentMethod ?: "???"
        
        holder.tvId.text = "Venda #${items.size - position}"
        holder.tvTime.text = time
        
        val displayTotal = if (item.currency != null && item.convertedTotal != null) {
            CurrencyManager.getInstance().formatExplicit(item.convertedTotal, item.currency)
        } else {
            CurrencyManager.getInstance().format(item.total)
        }
        holder.tvTotal.text = displayTotal
        val currency = item.currency?.uppercase() ?: "BRL"
        holder.tvMethod.text = "${method.uppercase()} • $currency"
        
        // Dynamic color/indicator if needed
        holder.statusIndicator.setBackgroundColor(
            if (method.uppercase().contains("PIX")) 0xFF00ACC1.toInt() 
            else if (method.uppercase().contains("CARD") || method.uppercase().contains("CARTAO")) 0xFF3949AB.toInt()
            else 0xFFF4511E.toInt()
        )
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvId: TextView = itemView.findViewById(R.id.tvSaleId)
        val tvTime: TextView = itemView.findViewById(R.id.tvSaleTime)
        val tvTotal: TextView = itemView.findViewById(R.id.tvSaleTotal)
        val tvMethod: TextView = itemView.findViewById(R.id.tvSaleMethod)
        val statusIndicator: View = itemView.findViewById(R.id.statusIndicator)
    }
}
