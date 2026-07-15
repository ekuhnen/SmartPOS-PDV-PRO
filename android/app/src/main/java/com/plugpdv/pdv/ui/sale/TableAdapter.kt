package com.plugpdv.pdv.ui.sale

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.utils.CurrencyManager

class TableAdapter(
    private var tables: MutableList<Table>,
    private val listener: (Table) -> Unit,
    private val onTransferClick: ((Table) -> Unit)? = null
) : RecyclerView.Adapter<TableAdapter.ViewHolder>() {

    fun setTables(newTables: List<Table>) {
        this.tables = newTables.toMutableList()
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_table, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val table = tables[position]
        holder.bind(table, listener, onTransferClick)
    }

    override fun getItemCount(): Int = tables.size

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvNumber: TextView = itemView.findViewById(R.id.tvTableNumber)
        private val vBadge: View = itemView.findViewById(R.id.vStatusBadge)
        private val tvCapacity: TextView = itemView.findViewById(R.id.tvCapacity)
        private val tvSectorName: TextView = itemView.findViewById(R.id.tvSectorName)
        private val ivTransfer: android.widget.ImageView = itemView.findViewById(R.id.ivTransfer)
        private val cardView: com.google.android.material.card.MaterialCardView = itemView.findViewById(R.id.cardView)

        fun bind(table: Table, listener: (Table) -> Unit, onTransferClick: ((Table) -> Unit)?) {
            // number is an Int, just convert to string
            tvNumber.text = table.number.toString()
            
            // Set Badge Color based on status
            val badgeColor = when (table.status) {
                Table.Status.OCCUPIED -> R.color.primary // Orange
                Table.Status.RESERVED -> R.color.warning // Yellow/Amber
                else -> R.color.success // Green
            }
            vBadge.backgroundTintList = ContextCompat.getColorStateList(itemView.context, badgeColor)
            
            // Set card to white (Premium reference style)
            cardView.setCardBackgroundColor(ContextCompat.getColor(itemView.context, R.color.surface))
            
            // Use people_count from model
            tvCapacity.text = "${table.people_count}xp"
            
            // Set Sector Name
            tvSectorName.text = table.sectorName
            tvSectorName.visibility = if (table.sectorName.isEmpty()) View.GONE else View.VISIBLE
            
            if (table.status == Table.Status.OCCUPIED) {
                ivTransfer.visibility = View.VISIBLE
                ivTransfer.setOnClickListener {
                    onTransferClick?.invoke(table)
                }
            } else {
                ivTransfer.visibility = View.GONE
                ivTransfer.setOnClickListener(null)
            }
            
            itemView.setOnClickListener { listener(table) }
        }
    }
}
