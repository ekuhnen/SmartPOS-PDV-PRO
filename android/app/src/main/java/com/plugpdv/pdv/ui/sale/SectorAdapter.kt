package com.plugpdv.pdv.ui.sale

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.Sector

class SectorAdapter(private val listener: (Sector) -> Unit) : RecyclerView.Adapter<SectorAdapter.ViewHolder>() {
    private var sectors = mutableListOf<Sector>()
    private var selectedSectorId: String? = null

    fun setSectors(newSectors: List<Sector>) {
        this.sectors.clear()
        this.sectors.addAll(newSectors)
        notifyDataSetChanged()
    }

    fun setSelectedSector(sectorId: String?) {
        this.selectedSectorId = sectorId
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_category, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val sector = sectors[position]
        holder.tvName.text = sector.nome

        val isSelected = sector.id == (selectedSectorId ?: "")
        holder.tvName.setBackgroundResource(
            if (isSelected) R.drawable.bg_category_selected else R.drawable.bg_category_unselected
        )
        holder.tvName.setTextColor(
            if (isSelected) Color.WHITE else holder.itemView.context.getColor(R.color.text_secondary)
        )

        holder.itemView.setOnClickListener { listener(sector) }
    }

    override fun getItemCount(): Int = sectors.size

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvName: TextView = view.findViewById(R.id.tvCategoryName)
    }
}
