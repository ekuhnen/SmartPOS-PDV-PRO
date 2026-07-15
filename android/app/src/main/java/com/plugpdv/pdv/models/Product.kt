package com.plugpdv.pdv.models

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.gson.annotations.SerializedName
import java.io.Serializable

@Entity(tableName = "products")
data class Product(
    @PrimaryKey
    @SerializedName(value = "id", alternate = ["product_id", "id_produto"])
    var id: String = "",
    
    @SerializedName(value = "name", alternate = ["nome", "product_name", "nome_produto"])
    var name: String? = "",
    
    var sku: String? = null,
    var barcode: String? = null,
    var category: String? = null,
    
    @SerializedName(value = "selling_price", alternate = ["price", "preco", "valor", "unit_price", "valor_unitario"])
    var selling_price: Double? = 0.0,
    
    var stock: Int? = 0,
    var image_url: String? = null
) : Serializable
