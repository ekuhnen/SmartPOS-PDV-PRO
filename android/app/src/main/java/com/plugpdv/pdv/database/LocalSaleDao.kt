package com.plugpdv.pdv.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LocalSaleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(sale: LocalSaleEntity)

    @Query("UPDATE local_sales SET syncedToApi = 1, apiId = :apiId WHERE localId = :localId")
    suspend fun markAsSynced(localId: String, apiId: String)

    @Query("SELECT * FROM local_sales WHERE syncedToApi = 0 ORDER BY timestamp DESC")
    suspend fun getPendingSync(): List<LocalSaleEntity>

    @Query("SELECT * FROM local_sales ORDER BY timestamp DESC LIMIT 50")
    suspend fun getRecentSales(): List<LocalSaleEntity>
}
