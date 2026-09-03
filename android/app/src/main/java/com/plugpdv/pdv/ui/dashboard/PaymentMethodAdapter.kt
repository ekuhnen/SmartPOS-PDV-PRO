package com.plugpdv.pdv.ui.dashboard

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.DashboardDisplayTotal
import com.plugpdv.pdv.utils.CurrencyManager

class PaymentMethodAdapter(private var items: List<DashboardDisplayTotal>) : RecyclerView.Adapter<PaymentMethodAdapter.ViewHolder>() {
    fun updateData(newItems: List<DashboardDisplayTotal>) { items = newItems; notifyDataSetChanged() }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_payment_method_tile, parent, false))
    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.name.text = item.label
        holder.total.text = CurrencyManager.getInstance().formatMinorUnits(item.money.amountMinor, item.money.currency)
        holder.icon.setImageResource(R.drawable.ic_attach_money)
    }
    override fun getItemCount() = items.size
    class ViewHolder(view: android.view.View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.ivMethodIcon)
        val name: TextView = view.findViewById(R.id.tvMethodName)
        val total: TextView = view.findViewById(R.id.tvMethodTotal)
    }
}
