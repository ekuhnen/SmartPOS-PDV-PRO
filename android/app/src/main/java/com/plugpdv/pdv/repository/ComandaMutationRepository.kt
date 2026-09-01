package com.plugpdv.pdv.repository

import android.content.Context
import androidx.room.withTransaction
import com.google.gson.Gson
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.ComandaMutationDao
import com.plugpdv.pdv.database.ComandaMutationEntity
import com.plugpdv.pdv.database.ComandaSnapshotDao
import com.plugpdv.pdv.database.ComandaSnapshotEntity
import com.plugpdv.pdv.database.TableDao
import com.plugpdv.pdv.database.TableEntity
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.worker.ComandaWorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

sealed class OpenTableResult {
    data class Accepted(
        val localComandaId: String,
        val mutationId: String,
        val isAlreadyAccepted: Boolean = false
    ) : OpenTableResult()

    data class ExistingServerComanda(
        val serverComandaId: String
    ) : OpenTableResult()

    data class Rejected(
        val reason: String,
        val messageKey: String? = null
    ) : OpenTableResult()
}

@Singleton
class ComandaMutationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val tableDao: TableDao,
    private val comandaMutationDao: ComandaMutationDao,
    private val comandaSnapshotDao: ComandaSnapshotDao,
    private val workScheduler: ComandaWorkScheduler
) {
    private val gson = Gson()

    suspend fun openTableDurable(
        tableId: String,
        customerName: String,
        actorUserId: String,
        deviceId: String,
        tenantId: String,
        peopleCount: Int = 1
    ): OpenTableResult {
        // B7: Rejeitar identificadores nulos, vazios ou sentinelas inválidos
        if (tableId.isBlank() || actorUserId.isBlank() || actorUserId.equals("UNKNOWN", ignoreCase = true) || deviceId.isBlank() || tenantId.isBlank()) {
            return OpenTableResult.Rejected("Missing required identity or authority", "missing_authority")
        }

        // B9: Autoridade local do modo de operação Mesa (fail-closed)
        val prefs = context.getSharedPreferences(com.plugpdv.pdv.utils.Constants.PREFS_NAME, Context.MODE_PRIVATE)
        if (!prefs.contains(com.plugpdv.pdv.utils.Constants.HAS_MESA)) {
            return OpenTableResult.Rejected("Mesa operation mode authority is unknown", "mode_authority_unknown")
        }
        val hasMesa = prefs.getBoolean(com.plugpdv.pdv.utils.Constants.HAS_MESA, false)
        if (!hasMesa) {
            return OpenTableResult.Rejected("Mesa mode is disabled for this enterprise", "operation_mode_disabled")
        }

        val result = database.withTransaction {
            val existingTable = tableDao.getTableById(tableId)
                ?: return@withTransaction OpenTableResult.Rejected("Table not found locally", "table_not_found")

            // 1. Caso a mesa já possua uma comanda canônica remota
            if (!existingTable.comandaId.isNullOrBlank()) {
                return@withTransaction OpenTableResult.ExistingServerComanda(existingTable.comandaId)
            }

            // 2. Proteção contra duplo toque: se a mesa já possui localComandaId e mutação pendente
            if (existingTable.status == Table.Status.OCCUPIED && !existingTable.localComandaId.isNullOrBlank()) {
                val pendingMutation = comandaMutationDao.getPendingOpenForTable(tableId)
                if (pendingMutation != null) {
                    return@withTransaction OpenTableResult.Accepted(
                        localComandaId = existingTable.localComandaId,
                        mutationId = pendingMutation.id,
                        isAlreadyAccepted = true
                    )
                }
            }

            // 3. Mesa ocupada sem vínculo identificado (conflito)
            if (existingTable.status == Table.Status.OCCUPIED && existingTable.localComandaId.isNullOrBlank()) {
                return@withTransaction OpenTableResult.Rejected("Table is already occupied with ambiguous state", "table_occupied_conflict")
            }

            val localComandaId = "loc_cmd_" + UUID.randomUUID().toString()
            val mutationId = "mut_open_" + UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            val payload = mapOf(
                "action" to "abrir",
                "mesa_id" to tableId,
                "nome_cliente" to customerName,
                "pessoas_qtd" to peopleCount
            )
            val payloadJson = gson.toJson(payload)

            // B5/B12: Criar Snapshot local sem fabricar moeda/dígitos nem valores financeiros
            val snapshot = ComandaSnapshotEntity(
                localComandaId = localComandaId,
                serverComandaId = null,
                tenantId = tenantId,
                tableId = tableId,
                tableNumber = existingTable.number,
                customerIdentifier = customerName,
                baseCurrency = null,
                baseMinorUnitDigits = null,
                serverStatus = null,
                localStatus = "OPEN",
                syncStatus = "PENDING",
                serverRevision = null,
                localRevision = 1L,
                totalBaseMinor = null,
                paidBaseMinor = null,
                balanceBaseMinor = null,
                itemsJson = "[]",
                paymentsJson = "[]",
                requiresReconciliation = false,
                reconciliationReason = null,
                serverUpdatedAt = null,
                cachedAt = now
            )
            comandaSnapshotDao.upsert(snapshot)

            // Atualizar projeção da TableEntity
            val updatedTable = existingTable.copy(
                status = Table.Status.OCCUPIED,
                localComandaId = localComandaId,
                customerName = customerName.ifBlank { existingTable.customerName },
                peopleCount = peopleCount,
                updatedAt = now
            )
            tableDao.insert(updatedTable)

            // Inserir mutação durável de abertura
            val mutation = ComandaMutationEntity(
                id = mutationId,
                operationType = "OPEN_TABLE",
                tenantId = tenantId,
                actorUserId = actorUserId,
                deviceId = deviceId,
                localComandaId = localComandaId,
                tableId = tableId,
                localItemId = null,
                payloadJson = payloadJson,
                resolvedPayloadJson = payloadJson,
                createdAt = now,
                updatedAt = now,
                attemptCount = 0,
                lastAttemptAt = null,
                nextRetryAt = now,
                status = "PENDING",
                pauseReason = null,
                reconciliationReason = null,
                claimToken = null,
                claimedAt = null,
                lastErrorCode = null,
                messageKey = null
            )
            comandaMutationDao.insert(mutation)

            OpenTableResult.Accepted(
                localComandaId = localComandaId,
                mutationId = mutationId,
                isAlreadyAccepted = false
            )
        }

        if (result is OpenTableResult.Accepted && !result.isAlreadyAccepted) {
            try {
                workScheduler.scheduleCommandSync()
            } catch (e: Exception) {
                // Background scheduling is best effort; acceptance is already durably committed
            }
        }

        return result
    }

    /**
     * Resumes eligible PAUSED comanda mutations after a successful authenticated login.
     * Only transitions rows where tenantId, actorUserId, and deviceId all match, for AUTH_REQUIRED/DIFFERENT_ACTOR.
     */
    suspend fun resumeAfterAuthenticatedLogin(
        tenantId: String,
        actorUserId: String,
        deviceId: String
    ): Int {
        if (tenantId.isBlank() || actorUserId.isBlank() || deviceId.isBlank()) return 0
        val now = System.currentTimeMillis()
        return comandaMutationDao.resumeAfterAuthenticatedLogin(
            tenantId = tenantId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            now = now
        )
    }

    /**
     * Resumes eligible PAUSED comanda mutations after verified terminal device authorization.
     * Only transitions rows where tenantId, actorUserId, and deviceId all match, for DEVICE_BLOCKED/DEVICE_NOT_REGISTERED.
     */
    suspend fun resumeAfterVerifiedDeviceAuthorization(
        tenantId: String,
        actorUserId: String,
        deviceId: String
    ): Int {
        if (tenantId.isBlank() || actorUserId.isBlank() || deviceId.isBlank()) return 0
        val now = System.currentTimeMillis()
        return comandaMutationDao.resumeAfterVerifiedDeviceAuthorization(
            tenantId = tenantId,
            actorUserId = actorUserId,
            deviceId = deviceId,
            now = now
        )
    }
}
