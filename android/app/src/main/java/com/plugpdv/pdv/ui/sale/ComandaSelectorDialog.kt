package com.plugpdv.pdv.ui.sale

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.plugpdv.pdv.R
import com.plugpdv.pdv.models.ComandaDiscoveryItem

object ComandaSelectorDialog {
    fun show(context: Context, mesaTitle: String?, items: List<ComandaDiscoveryItem>, onSelected: (ComandaDiscoveryItem) -> Unit) {
        val title = mesaTitle?.let { "$it · ${context.getString(R.string.open_comandas)}" }
            ?: context.getString(R.string.select_comanda)
        val labels = items.map { item ->
            val mesa = item.mesaNumero?.let { "Mesa $it" } ?: ""
            val name = item.displayLabel
            val control = item.controlCode?.let { " · Comanda $it" } ?: ""
            listOf(mesa, name).filter { it.isNotBlank() }.joinToString(" · ") + control
        }.toTypedArray()
        AlertDialog.Builder(context)
            .setTitle(title)
            .setItems(labels) { _, which -> onSelected(items[which]) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
