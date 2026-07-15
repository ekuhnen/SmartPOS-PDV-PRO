package com.plugpdv.pdv.database

import androidx.lifecycle.LiveData
import androidx.room.*
import com.plugpdv.pdv.models.Product

@Dao
interface CatalogDao {
    @get:Query("SELECT * FROM products")
    val allProductsLiveData: LiveData<List<Product>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(products: List<Product>)

    @Query("SELECT * FROM products WHERE id = :id")
    suspend fun getProductById(id: String): Product?

    @Query("DELETE FROM products")
    suspend fun deleteAll()
}
