package com.plugpdv.pdv.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ComandaSnapshotDao {

    @Query("SELECT * FROM comanda_snapshots WHERE localComandaId = :localId")
    suspend fun getByLocalId(localId: String): ComandaSnapshotEntity?

    @Query("SELECT * FROM comanda_snapshots WHERE tenantId = :tenantId AND serverComandaId = :serverComandaId")
    suspend fun getByServerComandaId(
        tenantId: String,
        serverComandaId: String
    ): ComandaSnapshotEntity?

    @Query("SELECT * FROM comanda_snapshots WHERE tenantId = :tenantId AND tableId = :tableId")
    suspend fun getByTableId(
        tenantId: String,
        tableId: String
    ): ComandaSnapshotEntity?

    @Query("SELECT * FROM comanda_snapshots WHERE tenantId = :tenantId AND tableId = :tableId")
    suspend fun getSnapshotsByTableId(
        tenantId: String,
        tableId: String
    ): List<ComandaSnapshotEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: ComandaSnapshotEntity)

    @Query("SELECT * FROM comanda_snapshots WHERE tenantId = :tenantId")
    suspend fun getAllForTenant(
        tenantId: String
    ): List<ComandaSnapshotEntity>

    @Query("DELETE FROM comanda_snapshots WHERE tenantId = :tenantId")
    suspend fun deleteForTenant(tenantId: String)

    @Query("DELETE FROM comanda_snapshots")
    suspend fun deleteAll()
}
