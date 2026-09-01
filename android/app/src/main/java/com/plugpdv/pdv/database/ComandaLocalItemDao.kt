package com.plugpdv.pdv.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface ComandaLocalItemDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: ComandaLocalItemEntity)

    @Update
    suspend fun update(item: ComandaLocalItemEntity)

    @Query("SELECT * FROM comanda_local_items WHERE localItemId = :localItemId")
    suspend fun getByLocalItemId(localItemId: String): ComandaLocalItemEntity?

    @Query("SELECT * FROM comanda_local_items WHERE tenantId = :tenantId AND serverItemId = :serverItemId LIMIT 1")
    suspend fun getByServerItemId(tenantId: String, serverItemId: String): ComandaLocalItemEntity?

    @Query("SELECT * FROM comanda_local_items WHERE localComandaId = :localComandaId ORDER BY createdAt ASC")
    suspend fun getItemsForComanda(localComandaId: String): List<ComandaLocalItemEntity>
}
