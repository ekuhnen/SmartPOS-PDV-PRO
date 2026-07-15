package com.plugpdv.pdv.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "taxes")
class TaxEntity : Serializable {
    @PrimaryKey
    var id: String = ""
    var name: String = ""
    var percentage: Double = 0.0
    var currency: String = ""
    var active: Boolean = false
}
