package com.plugpdv.pdv.ui.sale

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.R
import com.plugpdv.pdv.utils.CurrencyManager

class CartAdapter(
    private var items: MutableList<SaleViewModel.CartItem>,
    private val listener: OnCartItemListener
) : RecyclerView.Adapter<CartAdapter.ViewHolder>() {

    interface OnCartItemListener {
        fun onIncrease(item: SaleViewModel.CartItem)
        fun onDecrease(item: SaleViewModel.CartItem)
    }

    fun setItems(newItems: List<SaleViewModel.CartItem>?) {
        this.items = (newItems ?: emptyList()).toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_cart, parent, false)
        return ViewHolder(v)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        
        holder.tvName.text = item.product.name
        holder.tvPrice.text = CurrencyManager.getInstance().format(item.product.selling_price ?: 0.0)
        holder.tvQuantity.text = item.quantity.toString()

        holder.btnAdd.setOnClickListener { listener.onIncrease(item) }
        holder.btnRemove.setOnClickListener { listener.onDecrease(item) }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvName: TextView = itemView.findViewById(R.id.tvCartProductName)
        val tvPrice: TextView = itemView.findViewById(R.id.tvCartProductPrice)
        val tvQuantity: TextView = itemView.findViewById(R.id.tvCartQuantity)
        val btnAdd: ImageButton = itemView.findViewById(R.id.btnAddQty)
        val btnRemove: ImageButton = itemView.findViewById(R.id.btnRemoveQty)
    }
}
