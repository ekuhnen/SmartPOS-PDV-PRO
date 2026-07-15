package com.plugpdv.pdv.ui.dashboard

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.PaymentMethodSummary
import com.plugpdv.pdv.utils.CurrencyManager

class PaymentMethodAdapter(private var items: List<PaymentMethodSummary>) : 
    RecyclerView.Adapter<PaymentMethodAdapter.ViewHolder>() {

    fun updateData(newItems: List<PaymentMethodSummary>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_payment_method_tile, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvName.text = item.name
        
        val displayTotal = if (item.currencyCode != null) {
            CurrencyManager.getInstance().formatExplicit(item.total, item.currencyCode)
        } else {
            CurrencyManager.getInstance().format(item.total)
        }
        
        holder.tvTotal.text = displayTotal
        holder.ivIcon.setImageResource(item.iconRes)
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val ivIcon: ImageView = itemView.findViewById(R.id.ivMethodIcon)
        val tvName: TextView = itemView.findViewById(R.id.tvMethodName)
        val tvTotal: TextView = itemView.findViewById(R.id.tvMethodTotal)
    }
}
