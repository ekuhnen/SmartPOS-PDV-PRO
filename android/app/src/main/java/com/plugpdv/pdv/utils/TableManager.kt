package com.plugpdv.pdv.utils

import com.plugpdv.pdv.models.Table

object TableManager {
    private val tables = mutableListOf<Table>()

    @JvmStatic
    fun getTables(): List<Table> = tables

    @JvmStatic
    fun setTables(newTables: List<Table>) {
        // Save current paid info to restore after refresh for occupied tables
        val localPaidInfo = tables.filter { it.id != null && it.status == Table.Status.OCCUPIED }.associate { table ->
            table.id!! to (table.paidAmount to table.items.associate { it.product.id to it.paidQuantity })
        }

        tables.clear()
        newTables.forEach { newTable ->
            if (newTable.status == Table.Status.OCCUPIED) {
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
                    if (newTable.getPendingBalance() <= 0.01 && newTable.total > 0.0) {
                        newTable.status = Table.Status.AVAILABLE
                        newTable.comandaId = null
                        newTable.customerName = ""
                        newTable.paidAmount = 0.0
                        newTable.items.clear()
                    }
                }
            } else {
                newTable.paidAmount = 0.0
            }
            tables.add(newTable)
        }
    }

    @JvmStatic
    fun getTableById(id: String?): Table? {
        if (id.isNullOrEmpty()) return null
        return tables.find { it.id == id }
    }

    @JvmStatic
    fun getTableByNumber(number: Int, sectorId: String? = null): Table? {
        if (!sectorId.isNullOrEmpty()) {
            val match = tables.find { it.number == number && it.sectorId == sectorId }
            if (match != null) return match
        }
        return tables.find { it.number == number }
    }

    @JvmStatic
    fun getTable(id: String?, number: Int, sectorId: String? = null): Table? {
        if (!id.isNullOrEmpty()) {
            val found = getTableById(id)
            if (found != null) return found
        }
        return getTableByNumber(number, sectorId)
    }

    @JvmStatic
    fun updateTable(updatedTable: Table) {
        val index = tables.indexOfFirst { existing ->
            if (!updatedTable.id.isNullOrEmpty() && !existing.id.isNullOrEmpty()) {
                existing.id == updatedTable.id
            } else if (!updatedTable.sectorId.isNullOrEmpty() && !existing.sectorId.isNullOrEmpty()) {
                existing.number == updatedTable.number && existing.sectorId == updatedTable.sectorId
            } else {
                existing.number == updatedTable.number
            }
        }
        if (index != -1) {
            tables[index] = updatedTable
        } else {
            tables.add(updatedTable)
        }
    }

    @JvmStatic
    fun updateTableStatus(id: String?, number: Int, status: String, sectorId: String? = null) {
        getTable(id, number, sectorId)?.let { it.status = status }
    }

    @JvmStatic
    fun markTableAvailable(tableId: String?) {
        if (tableId.isNullOrEmpty()) return
        getTableById(tableId)?.let { t ->
            t.status = Table.Status.AVAILABLE
            t.comandaId = null
            t.customerName = ""
            t.paidAmount = 0.0
            t.items.clear()
        }
    }
}
