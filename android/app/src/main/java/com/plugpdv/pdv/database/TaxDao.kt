package com.plugpdv.pdv.database

import androidx.lifecycle.LiveData
import androidx.room.*

@Dao
interface TaxDao {
    @get:Query("SELECT * FROM taxes WHERE active = 1")
    val activeTaxesLiveData: LiveData<List<TaxEntity>>

    @Query("SELECT * FROM taxes")
    suspend fun getAll(): List<TaxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(taxes: List<TaxEntity>)

    @Query("DELETE FROM taxes")
    suspend fun deleteAll()
}
