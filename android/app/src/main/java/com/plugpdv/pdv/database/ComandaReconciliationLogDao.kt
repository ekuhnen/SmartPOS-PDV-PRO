package com.plugpdv.pdv.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ComandaReconciliationLogDao {

    /**
     * Insert an audit log entry.
     * ABORT semantics: duplicate id MUST fail loudly — a log entry is immutable.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(log: ComandaReconciliationLogEntity)

    /**
     * Full audit trail for a given mutation, ordered chronologically.
     */
    @Query("SELECT * FROM comanda_reconciliation_log WHERE mutationId = :mutationId ORDER BY resolvedAt ASC")
    suspend fun getByMutationId(mutationId: String): List<ComandaReconciliationLogEntity>

    /**
     * All resolution actions for a tenant, most recent first.
     * Useful for a reconciliation dashboard in a future UI slice.
     */
    @Query("SELECT * FROM comanda_reconciliation_log WHERE tenantId = :tenantId ORDER BY resolvedAt DESC")
    suspend fun getByTenant(tenantId: String): List<ComandaReconciliationLogEntity>
}
