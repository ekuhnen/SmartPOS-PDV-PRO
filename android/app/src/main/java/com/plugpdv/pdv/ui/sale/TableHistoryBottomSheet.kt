package com.plugpdv.pdv.ui.sale

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.utils.TableManager
import java.text.SimpleDateFormat
import java.util.*

class TableHistoryBottomSheet : BottomSheetDialogFragment() {
    private var table: Table? = null
    private val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            val tableNumber = it.getInt("TABLE_NUMBER")
            table = TableManager.getTableByNumber(tableNumber)
        }
        if (table == null) {
            dismiss()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.layout_table_history, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val rvHistory = view.findViewById<RecyclerView>(R.id.rvHistory)
        rvHistory.layoutManager = LinearLayoutManager(context)
        
        val entries = generateLogEntries()
        rvHistory.adapter = HistoryAdapter(entries)
    }

    private fun generateLogEntries(): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        table?.items?.forEach { item ->
            val time = sdf.format(Date(item.timestamp))
            if (item.removed) {
                entries.add(LogEntry(time, "❌ REMOVIDO: ${item.product.name} (${item.removalReason})"))
            } else {
                entries.add(LogEntry(time, "✅ PEDIDO: ${item.product.name} (Qtd: ${item.quantity})"))
                if (item.paidQuantity > 0) {
                    entries.add(LogEntry(time, "💰 PAGO: ${item.product.name} (Qtd: ${item.paidQuantity})"))
                }
            }
        }
        return entries.reversed()
    }

    private data class LogEntry(val time: String, val description: String)

    private inner class HistoryAdapter(private val entries: List<LogEntry>) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val v = LayoutInflater.from(parent.context).inflate(R.layout.item_table_history_log, parent, false)
            return ViewHolder(v)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val entry = entries[position]
            holder.tvTime.text = entry.time
            holder.tvDescription.text = entry.description
        }

        override fun getItemCount(): Int = entries.size

        inner class ViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val tvTime: TextView = v.findViewById(R.id.tvLogTime)
            val tvDescription: TextView = v.findViewById(R.id.tvLogDescription)
        }
    }

    companion object {
        @JvmStatic
        fun newInstance(tableNumber: Int): TableHistoryBottomSheet {
            return TableHistoryBottomSheet().apply {
                arguments = Bundle().apply {
                    putInt("TABLE_NUMBER", tableNumber)
                }
            }
        }
    }
}
