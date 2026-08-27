package com.plugpdv.pdv.ui.sale

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.Product
import com.plugpdv.pdv.utils.CurrencyManager
import com.bumptech.glide.Glide

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter

class ProductAdapter(private val listener: (Product) -> Unit) : ListAdapter<Product, ProductAdapter.ProductViewHolder>(ProductDiffCallback()) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProductViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_product, parent, false)
        return ProductViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProductViewHolder, position: Int) {
        val product = getItem(position)
        holder.bind(product, listener)
    }

    class ProductDiffCallback : DiffUtil.ItemCallback<Product>() {
        override fun areItemsTheSame(oldItem: Product, newItem: Product): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Product, newItem: Product): Boolean {
            return oldItem == newItem
        }
    }

    class ProductViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvName: TextView = itemView.findViewById(R.id.tvProductName)
        private val tvPrice: TextView = itemView.findViewById(R.id.tvProductPrice)
        private val tvStock: TextView = itemView.findViewById(R.id.tvProductStock)
        private val ivProduct: ImageView = itemView.findViewById(R.id.ivProductImage)
        private val btnInfo: android.widget.ImageButton = itemView.findViewById(R.id.btnProductInfo)

        fun bind(product: Product, listener: (Product) -> Unit) {
            tvName.text = product.name ?: "Sem Nome"
            tvPrice.text = CurrencyManager.getInstance().format(product.selling_price ?: 0.0)
            tvStock.text = itemView.context.getString(R.string.stock_label, product.stock ?: 0)
            
            Glide.with(itemView.context)
                .load(product.image_url)
                .diskCacheStrategy(com.bumptech.glide.load.engine.DiskCacheStrategy.ALL)
                .placeholder(R.drawable.ic_placeholder_product)
                .error(R.drawable.ic_placeholder_product)
                .centerCrop()
                .into(ivProduct)

            btnInfo.setOnClickListener {
                showProductCurrenciesDialog(itemView.context, product)
            }

            itemView.setOnClickListener { listener(product) }
        }

        private fun showProductCurrenciesDialog(context: android.content.Context, product: Product) {
            val cm = CurrencyManager.getInstance()
            val baseCurrency = cm.getBaseCurrency()
            val sellingPrice = product.selling_price ?: 0.0

            val currenciesList = mutableListOf<Pair<String, String>>()
            
            val available = cm.getAvailableCurrencies()
            val hasBase = available.any { it.codigo.equals(baseCurrency, ignoreCase = true) }
            
            if (!hasBase) {
                currenciesList.add(baseCurrency to cm.formatExplicit(sellingPrice, baseCurrency))
            }
            
            available.forEach { rate ->
                val converted = cm.fromBrl(sellingPrice, rate.codigo)
                currenciesList.add(rate.codigo to cm.formatExplicit(converted, rate.codigo))
            }

            val container = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                val padding = (24 * context.resources.displayMetrics.density).toInt()
                setPadding(padding, padding, padding, padding)
            }

            currenciesList.forEach { (code, price) ->
                val row = android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, (12 * context.resources.displayMetrics.density).toInt())
                    }
                }

                val tvCode = android.widget.TextView(context).apply {
                    text = code
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(context.getColor(R.color.text_primary))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        0,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        1f
                    )
                }

                val tvPrice = android.widget.TextView(context).apply {
                    text = price
                    textSize = 16f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(context.getColor(R.color.brand_orange))
                }

                row.addView(tvCode)
                row.addView(tvPrice)
                container.addView(row)
            }

            androidx.appcompat.app.AlertDialog.Builder(context)
                .setTitle(product.name ?: "Produto")
                .setView(container)
                .setPositiveButton("Fechar", null)
                .show()
        }
    }
}
