package com.plugpdv.pdv.ui.reconciliation

import com.plugpdv.pdv.R
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
        holder.binding.tvTargetIdentifier.text = holder.itemView.context.getString(R.string.table_label_value, item.tableId.removePrefix("tbl_"))
        holder.binding.tvOperationType.setText(ReconciliationReasonMapper.operationTypeRes(item.operationType))
        holder.binding.tvReasonHuman.setText(ReconciliationReasonMapper.messageRes(item.reconciliationReason ?: item.lastErrorCode))

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
