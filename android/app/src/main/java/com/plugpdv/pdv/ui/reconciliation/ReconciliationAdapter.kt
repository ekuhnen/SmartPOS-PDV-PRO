package com.plugpdv.pdv.ui.reconciliation

import android.text.format.DateFormat
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.plugpdv.pdv.database.ComandaMutationEntity
import com.plugpdv.pdv.databinding.ItemReconciliationBinding
import java.util.Date

class ReconciliationAdapter(
    private var items: List<ComandaMutationEntity>,
    private val onItemClick: (ComandaMutationEntity) -> Unit
) : RecyclerView.Adapter<ReconciliationAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemReconciliationBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemReconciliationBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]

        // Human readable target identifier
        holder.binding.tvTargetIdentifier.text = "Mesa ${item.tableId.removePrefix("tbl_")}"
        holder.binding.tvOperationType.text = ReconciliationReasonMapper.toHumanOperationType(item.operationType)
        holder.binding.tvReasonHuman.text = ReconciliationReasonMapper.toHumanMessage(item.reconciliationReason ?: item.lastErrorCode)

        val dateFormatted = DateFormat.format("dd/MM/yyyy, HH:mm", Date(item.createdAt)).toString()
        holder.binding.tvTimestamp.text = dateFormatted

        holder.itemView.setOnClickListener { onItemClick(item) }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<ComandaMutationEntity>) {
        items = newItems
        notifyDataSetChanged()
    }
}
