package com.plugpdv.pdv.ui.sale

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.R
import com.plugpdv.pdv.database.TaxEntity
import com.plugpdv.pdv.utils.CurrencyManager

class CheckoutProductAdapter(
    private val items: MutableList<SaleViewModel.CartItem>,
    private val activeTaxes: List<TaxEntity>,
    private val listener: (List<SaleViewModel.CartItem>) -> Unit
) : RecyclerView.Adapter<CheckoutProductAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_checkout_product, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val cartItem = items[position]
        val cm = CurrencyManager.getInstance()
        val currentCurrency = cm.selectedCurrency

        holder.tvName.text = cartItem.product.name
        holder.tvQuantity.text = cartItem.quantity.toString()
        holder.tvUnitPrice.text = "Unit: ${cm.format(cartItem.product.selling_price ?: 0.0)}"

        val baseItemTotal = (cartItem.product.selling_price ?: 0.0) * cartItem.quantity
        
        holder.layoutTaxes.removeAllViews()
        var totalItemTaxes = 0.0
        activeTaxes.filter { it.currency.equals(currentCurrency, ignoreCase = true) }.forEach { tax ->
            val calculatedTax = baseItemTotal * (tax.percentage / 100.0)
            totalItemTaxes += calculatedTax
            
            val taxRow = LayoutInflater.from(holder.itemView.context).inflate(R.layout.item_tax_row, holder.layoutTaxes, false)
            taxRow.findViewById<TextView>(R.id.tvLabel).text = "${tax.name} (${String.format("%.1f%%", tax.percentage)})"
            taxRow.findViewById<TextView>(R.id.tvValue).text = cm.format(calculatedTax)
            holder.layoutTaxes.addView(taxRow)
        }

        holder.tvItemTotal.text = cm.format(baseItemTotal + totalItemTaxes)

        holder.btnPlus.setOnClickListener {
            cartItem.quantity++
            notifyItemChanged(position)
            listener(items)
        }

        holder.btnMinus.setOnClickListener {
            if (cartItem.quantity > 1) {
                cartItem.quantity--
                notifyItemChanged(position)
                listener(items)
            }
        }

        holder.btnRemove.setOnClickListener {
            items.removeAt(position)
            notifyItemRemoved(position)
            notifyItemRangeChanged(position, items.size)
            listener(items)
        }
    }

    override fun getItemCount(): Int = items.size

    class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
        val tvName: TextView = v.findViewById(R.id.tvProductName)
        val tvUnitPrice: TextView = v.findViewById(R.id.tvUnitPrice)
        val tvQuantity: TextView = v.findViewById(R.id.tvQuantity)
        val tvItemTotal: TextView = v.findViewById(R.id.tvItemTotal)
        val btnPlus: ImageButton = v.findViewById(R.id.btnPlus)
        val btnMinus: ImageButton = v.findViewById(R.id.btnMinus)
        val btnRemove: ImageView = v.findViewById(R.id.btnRemove)
        val layoutTaxes: LinearLayout = v.findViewById(R.id.layoutProductTaxes)
    }
}
