package com.plugpdv.pdv.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ComandaMutationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mutation: ComandaMutationEntity)

    @Query("SELECT * FROM comanda_mutations WHERE id = :id")
    suspend fun getById(id: String): ComandaMutationEntity?

    @Query("SELECT * FROM comanda_mutations WHERE localComandaId = :localComandaId ORDER BY createdAt ASC")
    suspend fun getByLocalComandaId(localComandaId: String): List<ComandaMutationEntity>

    @Query("SELECT COUNT(*) FROM comanda_mutations WHERE status != 'SYNCED'")
    suspend fun getUnresolvedCount(): Int
}
