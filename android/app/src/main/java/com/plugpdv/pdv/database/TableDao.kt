package com.plugpdv.pdv.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TableDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tables: List<TableEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(table: TableEntity)

    @Query("SELECT * FROM tables ORDER BY sectorName, number")
    fun observeAllTables(): Flow<List<TableEntity>>

    @Query("SELECT * FROM tables ORDER BY number ASC")
    suspend fun getAllTables(): List<TableEntity>

    @Query("SELECT * FROM tables WHERE status = 'OCCUPIED' ORDER BY number ASC")
    suspend fun getOccupiedTables(): List<TableEntity>

    @Query("SELECT * FROM tables WHERE id = :id LIMIT 1")
    suspend fun getTableById(id: String): TableEntity?

    @Query("SELECT * FROM tables WHERE number = :number AND sectorId = :sectorId LIMIT 1")
    suspend fun getTableByNumberAndSector(number: Int, sectorId: String): TableEntity?

    @Query("SELECT * FROM tables WHERE number = :number LIMIT 1")
    suspend fun getTableByNumber(number: Int): TableEntity?

    @Query("DELETE FROM tables")
    suspend fun deleteAll()
}
