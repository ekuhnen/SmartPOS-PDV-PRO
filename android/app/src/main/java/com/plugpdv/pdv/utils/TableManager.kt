package com.plugpdv.pdv.utils

import com.plugpdv.pdv.models.Table

object TableManager {
    private val tables = mutableListOf<Table>()

    @JvmStatic
    fun getTables(): List<Table> = tables

    @JvmStatic
    fun setTables(newTables: List<Table>) {
        // Save current paid info to restore after refresh
        val localPaidInfo = tables.filter { it.id != null }.associate { table ->
            table.id!! to (table.paidAmount to table.items.associate { it.product.id to it.paidQuantity })
        }

        tables.clear()
        newTables.forEach { newTable ->
            val info = localPaidInfo[newTable.id]
            if (info != null) {
                newTable.paidAmount = info.first
                newTable.items.forEach { item ->
                    item.paidQuantity = info.second[item.product.id] ?: 0
                    if (item.paidQuantity >= item.quantity) {
                        item.isPaid = true
                    }
                }
                newTable.calculateTotal()
            }
            tables.add(newTable)
        }
    }

    @JvmStatic
    fun getTableByNumber(number: Int): Table? {
        return tables.find { it.number == number }
    }

    @JvmStatic
    fun updateTable(updatedTable: Table) {
        val index = tables.indexOfFirst { it.number == updatedTable.number }
        if (index != -1) {
            tables[index] = updatedTable
        } else {
            tables.add(updatedTable)
        }
    }

    @JvmStatic
    fun updateTableStatus(number: Int, status: String) {
        getTableByNumber(number)?.let { it.status = status }
    }
}
