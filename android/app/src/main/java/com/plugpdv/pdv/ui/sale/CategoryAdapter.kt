package com.plugpdv.pdv.ui.sale

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.R

class CategoryAdapter(private val listener: (String) -> Unit) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {
    private var categories = mutableListOf<String>()
    private var selectedCategory = ""

    fun setCategories(newCategories: List<String>) {
        this.categories = mutableListOf("")
        this.categories.addAll(newCategories)
        notifyDataSetChanged()
    }

    fun setSelectedCategory(category: String) {
        this.selectedCategory = category
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val category = categories[position]
        holder.tvName.text = if (category.isNullOrEmpty()) {
            holder.itemView.context.getString(R.string.all_categories)
        } else {
            category
        }

        val isSelected = category == selectedCategory
        holder.tvName.setBackgroundResource(
            if (isSelected) R.drawable.bg_category_selected else R.drawable.bg_category_unselected
        )
        holder.tvName.setTextColor(
            if (isSelected) Color.WHITE else holder.itemView.context.getColor(R.color.text_secondary)
        )

        holder.itemView.setOnClickListener { listener(category) }
    }

    override fun getItemCount(): Int = categories.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvCategoryName)
    }
}
