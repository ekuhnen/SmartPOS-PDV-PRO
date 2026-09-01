package com.plugpdv.pdv.dispatcher

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.ComandaMutationDao
import com.plugpdv.pdv.database.ComandaMutationEntity
import com.plugpdv.pdv.database.ComandaSnapshotDao
import com.plugpdv.pdv.database.TableDao
import com.plugpdv.pdv.di.ComandaDispatcherService
import com.plugpdv.pdv.models.CommandActionRequest
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.CurrencyRulesProvider
import com.plugpdv.pdv.utils.DeviceIdProvider
import com.plugpdv.pdv.utils.TenantBindingStore
import com.plugpdv.pdv.worker.ComandaWorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

sealed class DispatchResult {
    data class Success(val serverComandaId: String) : DispatchResult()
    data class Retrying(val delayMs: Long, val reason: String) : DispatchResult()
    data class Paused(val reason: String) : DispatchResult()
    data class ReconciliationRequired(val reason: String) : DispatchResult()
    data class Skipped(val reason: String) : DispatchResult()
}

data class DispatchBatchResult(
    val processedCount: Int,
    val remainingCount: Int,
    val stopReason: String
)

data class BackendErrorResponse(
    val error: String? = null,
    val code: String? = null,
    val message: String? = null,
    @SerializedName("comanda_id") val comandaId: String? = null,
    @SerializedName("mesa_id") val mesaId: String? = null
)

@Singleton
class ComandaOutboxDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val comandaMutationDao: ComandaMutationDao,
    private val comandaSnapshotDao: ComandaSnapshotDao,
    private val tableDao: TableDao,
    @ComandaDispatcherService private val apiService: PosApiService,
    private val currencyRulesProvider: CurrencyRulesProvider,
    private val workScheduler: ComandaWorkScheduler
) {
    companion object {
        private const val TAG = "ComandaOutboxDispatcher"
        const val CALL_DEADLINE_SECONDS = 45L
        const val STALE_CLAIM_THRESHOLD_MS = 120_000L // 120 segundos
    }

    private val gson = Gson()

    suspend fun dispatchEligibleBatch(): DispatchBatchResult {
        val now = System.currentTimeMillis()
        val staleThreshold = now - STALE_CLAIM_THRESHOLD_MS

        // 1. Recuperar claims obsoletos (> 120s)
        val recovered = comandaMutationDao.recoverStaleProcessing(staleThreshold, now)
        if (recovered > 0) {
            Log.d(TAG, "Recuperados $recovered claims expirados (>120s)")
        }

        // 2. Tenant ativo
        val activeTenantId = TenantBindingStore.getActiveTenantId(context)
        if (activeTenantId.isNullOrBlank()) {
            return DispatchBatchResult(processedCount = 0, remainingCount = 0, stopReason = "NO_ACTIVE_TENANT")
        }

        // 3. Credenciais ativas da sessão
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val currentUserId = prefs.getString(Constants.USER_ID, null) ?: prefs.getString(Constants.OPERATOR_ID, null)
        val token = prefs.getString(Constants.TOKEN, null)

        if (!token.isNullOrBlank() && !currentUserId.isNullOrBlank()) {
            comandaMutationDao.unpauseEligibleMutations(activeTenantId, currentUserId, now)
        }

        // 4. Buscar mutações elegíveis
        val eligible = comandaMutationDao.getEligibleMutations(activeTenantId, now, staleThreshold)
        if (eligible.isEmpty()) {
            return DispatchBatchResult(processedCount = 0, remainingCount = 0, stopReason = "EMPTY")
        }

        var processed = 0
        for (mutation in eligible) {
            // No escopo 04A.2 despachamos estritamente OPEN_TABLE
            if (mutation.operationType == "OPEN_TABLE") {
                val result = dispatchOpenMutation(mutation, token, currentUserId)
                processed++
                if (result is DispatchResult.Paused || result is DispatchResult.ReconciliationRequired) {
                    if (result is DispatchResult.Paused && result.reason == "AUTH_REQUIRED") {
                        return DispatchBatchResult(processedCount = processed, remainingCount = eligible.size - processed, stopReason = "AUTH_REQUIRED")
                    }
                }
            }
        }

        val remaining = comandaMutationDao.getEligibleMutations(activeTenantId, System.currentTimeMillis(), staleThreshold).size
        return DispatchBatchResult(processedCount = processed, remainingCount = remaining, stopReason = "PROGRESSED")
    }

    suspend fun dispatchMutationById(mutationId: String): DispatchResult {
        val mutation = comandaMutationDao.getById(mutationId)
            ?: return DispatchResult.Skipped("Mutation not found")

        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val currentUserId = prefs.getString(Constants.USER_ID, null) ?: prefs.getString(Constants.OPERATOR_ID, null)
        val token = prefs.getString(Constants.TOKEN, null)

        return if (mutation.operationType == "OPEN_TABLE") {
            dispatchOpenMutation(mutation, token, currentUserId)
        } else {
            DispatchResult.Skipped("Operation type ${mutation.operationType} not supported in 04A.2")
        }
    }

    private suspend fun dispatchOpenMutation(
        mutation: ComandaMutationEntity,
        token: String?,
        currentUserId: String?
    ): DispatchResult {
        val now = System.currentTimeMillis()
        val staleThreshold = now - STALE_CLAIM_THRESHOLD_MS
        val claimToken = UUID.randomUUID().toString()

        // 1. Tentar claim atômico com token de posse exclusivo
        val claimed = comandaMutationDao.claimMutation(mutation.id, claimToken, now, staleThreshold)
        if (claimed == 0) {
            return DispatchResult.Skipped("Could not claim lease; already in processing or finalized")
        }

        // 2. Validação estrita de Tenant, Ator e Dispositivo antes de qualquer chamada HTTP (B3)
        val activeTenantId = TenantBindingStore.getActiveTenantId(context)
        if (activeTenantId.isNullOrBlank() || mutation.tenantId != activeTenantId) {
            comandaMutationDao.markPausedClaimed(mutation.id, claimToken, "DIFFERENT_TENANT", "different_tenant", now)
            return DispatchResult.Paused("DIFFERENT_TENANT")
        }

        if (token.isNullOrBlank()) {
            comandaMutationDao.markPausedClaimed(mutation.id, claimToken, "AUTH_REQUIRED", "auth_required", now)
            return DispatchResult.Paused("AUTH_REQUIRED")
        }

        if (mutation.actorUserId != currentUserId) {
            comandaMutationDao.markPausedClaimed(mutation.id, claimToken, "DIFFERENT_ACTOR", "different_actor", now)
            return DispatchResult.Paused("DIFFERENT_ACTOR")
        }

        val currentDeviceId = DeviceIdProvider.get(context)
        if (mutation.deviceId != currentDeviceId) {
            comandaMutationDao.markPausedClaimed(mutation.id, claimToken, "DEVICE_ID_MISMATCH", "device_mismatch", now)
            return DispatchResult.Paused("DEVICE_ID_MISMATCH")
        }

        // 3. Construção do request semântico a partir do payload congelado na aceitação (B8)
        val wireJson = mutation.resolvedPayloadJson ?: mutation.payloadJson
        val payloadMap = try {
            gson.fromJson(wireJson, Map::class.java)
        } catch (e: Exception) {
            null
        }

        val customerName = (payloadMap?.get("nome_cliente") as? String).orEmpty()
        val peopleCount = ((payloadMap?.get("pessoas_qtd") as? Number)?.toInt()) ?: 1

        val request = CommandActionRequest().apply {
            action = "abrir"
            mesaId = mutation.tableId
            nome_cliente = customerName
            people_count = peopleCount
        }

        // 4. Despacho com deadline de 45s e Idempotency-Key estável K
        try {
            val response = apiService.manageComanda("Bearer $token", request, idempotencyKey = mutation.id)
            val responseNow = System.currentTimeMillis()

            if (response.isSuccessful) {
                val body = response.body()
                val serverComandaId = (body?.get("id") ?: body?.get("comanda_id") ?: body?.get("comandaId")) as? String

                if (!serverComandaId.isNullOrBlank()) {
                    val rowsAffected = database.withTransaction {
                        val rows = comandaMutationDao.markSyncedClaimed(mutation.id, claimToken, responseNow)
                        if (rows > 0) {
                            // Conciliação de Snapshot e extração canônica de moeda (B5)
                            val existingSnapshot = comandaSnapshotDao.getByLocalId(mutation.localComandaId)
                            if (existingSnapshot != null) {
                                val serverCurrency = (body?.get("currency") ?: body?.get("moeda")) as? String
                                val serverDigits = serverCurrency?.let { currencyRulesProvider.getMinorUnitDigits(it) }

                                val updatedSnapshot = existingSnapshot.copy(
                                    serverComandaId = serverComandaId,
                                    serverStatus = "ABERTA",
                                    syncStatus = "SYNCED",
                                    baseCurrency = serverCurrency ?: existingSnapshot.baseCurrency,
                                    baseMinorUnitDigits = serverDigits ?: existingSnapshot.baseMinorUnitDigits,
                                    cachedAt = responseNow
                                )
                                comandaSnapshotDao.upsert(updatedSnapshot)
                            }

                            // Conciliação de TableEntity preservando localComandaId
                            val existingTable = tableDao.getTableById(mutation.tableId)
                            if (existingTable != null) {
                                val updatedTable = existingTable.copy(
                                    status = Table.Status.OCCUPIED,
                                    comandaId = serverComandaId,
                                    localComandaId = mutation.localComandaId,
                                    updatedAt = responseNow
                                )
                                tableDao.insert(updatedTable)
                            }
                        }
                        rows
                    }

                    if (rowsAffected == 0) {
                        Log.w(TAG, "Worker perdeu o lease durante sincronização de ${mutation.id}")
                        return DispatchResult.Skipped("Lost claim ownership")
                    }

                    Log.d(TAG, "OPEN_TABLE ${mutation.id} sincronizado com sucesso. serverComandaId: $serverComandaId")
                    return DispatchResult.Success(serverComandaId)
                } else {
                    Log.e(TAG, "OPEN_TABLE ${mutation.id} retornou 2xx mas com ID vazio no body")
                    comandaMutationDao.markReconciliationRequiredClaimed(mutation.id, claimToken, "EMPTY_SERVER_ID", "empty_server_id", responseNow)
                    return DispatchResult.ReconciliationRequired("EMPTY_SERVER_ID")
                }
            } else {
                val code = response.code()
                val errorBody = response.errorBody()?.string().orEmpty()
                return handleHttpError(mutation, claimToken, code, errorBody, responseNow)
            }
        } catch (e: Exception) {
            val excNow = System.currentTimeMillis()
            return handleException(mutation, claimToken, e, excNow)
        }
    }

    private suspend fun handleHttpError(
        mutation: ComandaMutationEntity,
        claimToken: String,
        code: Int,
        errorBody: String,
        now: Long
    ): DispatchResult {
        Log.w(TAG, "OPEN_TABLE ${mutation.id} HTTP $code: $errorBody")
        val backendError = try {
            gson.fromJson(errorBody, BackendErrorResponse::class.java)
        } catch (e: Exception) {
            null
        }
        val errorCode = (backendError?.code ?: backendError?.error).orEmpty().uppercase()

        return when (code) {
            401 -> {
                comandaMutationDao.markPausedClaimed(mutation.id, claimToken, "AUTH_REQUIRED", "auth_required", now)
                DispatchResult.Paused("AUTH_REQUIRED")
            }
            403 -> {
                when {
                    errorCode == "DEVICE_NOT_REGISTERED" || errorCode == "DEVICE_NOT_FOUND" -> {
                        comandaMutationDao.markPausedClaimed(mutation.id, claimToken, "DEVICE_NOT_REGISTERED", "device_not_registered", now)
                        DispatchResult.Paused("DEVICE_NOT_REGISTERED")
                    }
                    errorCode == "DEVICE_BLOCKED" -> {
                        comandaMutationDao.markPausedClaimed(mutation.id, claimToken, "DEVICE_BLOCKED", "device_blocked", now)
                        DispatchResult.Paused("DEVICE_BLOCKED")
                    }
                    errorCode == "TENANT_DEVICE_MISMATCH" -> {
                        comandaMutationDao.markPausedClaimed(mutation.id, claimToken, "TENANT_DEVICE_MISMATCH", "tenant_device_mismatch", now)
                        DispatchResult.Paused("TENANT_DEVICE_MISMATCH")
                    }
                    errorCode == "OPERATION_MODE_DISABLED" -> {
                        comandaMutationDao.markReconciliationRequiredClaimed(mutation.id, claimToken, "OPERATION_MODE_DISABLED", "mode_disabled", now)
                        DispatchResult.ReconciliationRequired("OPERATION_MODE_DISABLED")
                    }
                    else -> {
                        comandaMutationDao.markReconciliationRequiredClaimed(mutation.id, claimToken, "FORBIDDEN", "forbidden", now)
                        DispatchResult.ReconciliationRequired("FORBIDDEN")
                    }
                }
            }
            404 -> {
                comandaMutationDao.markReconciliationRequiredClaimed(mutation.id, claimToken, "MESA_NOT_FOUND", "mesa_not_found", now)
                DispatchResult.ReconciliationRequired("MESA_NOT_FOUND")
            }
            409 -> {
                when (errorCode) {
                    "OPERATION_IN_PROGRESS" -> {
                        val delayMs = 3_000L
                        comandaMutationDao.updateRetryClaimed(mutation.id, claimToken, now + delayMs, "OPERATION_IN_PROGRESS", "operation_in_progress", now)
                        workScheduler.scheduleRetry(delayMs)
                        DispatchResult.Retrying(delayMs, "OPERATION_IN_PROGRESS")
                    }
                    "TABLE_ALREADY_OCCUPIED" -> {
                        comandaMutationDao.markReconciliationRequiredClaimed(mutation.id, claimToken, "TABLE_ALREADY_OCCUPIED", "table_conflict", now)
                        DispatchResult.ReconciliationRequired("TABLE_ALREADY_OCCUPIED")
                    }
                    "MESA_INACTIVE" -> {
                        comandaMutationDao.markReconciliationRequiredClaimed(mutation.id, claimToken, "MESA_INACTIVE", "mesa_inactive", now)
                        DispatchResult.ReconciliationRequired("MESA_INACTIVE")
                    }
                    "IDEMPOTENCY_KEY_REUSED" -> {
                        comandaMutationDao.markReconciliationRequiredClaimed(mutation.id, claimToken, "IDEMPOTENCY_KEY_REUSED", "idempotency_conflict", now)
                        DispatchResult.ReconciliationRequired("IDEMPOTENCY_KEY_REUSED")
                    }
                    else -> {
                        val reason = errorCode.ifBlank { "TABLE_ALREADY_OCCUPIED" }
                        comandaMutationDao.markReconciliationRequiredClaimed(mutation.id, claimToken, reason, "table_conflict", now)
                        DispatchResult.ReconciliationRequired(reason)
                    }
                }
            }
            400 -> {
                when (errorCode) {
                    "DEVICE_ID_REQUIRED" -> {
                        comandaMutationDao.markPausedClaimed(mutation.id, claimToken, "DEVICE_ID_REQUIRED", "device_id_required", now)
                        DispatchResult.Paused("DEVICE_ID_REQUIRED")
                    }
                    "IDEMPOTENCY_KEY_INVALID" -> {
                        comandaMutationDao.markReconciliationRequiredClaimed(mutation.id, claimToken, "IDEMPOTENCY_KEY_INVALID", "idempotency_invalid", now)
                        DispatchResult.ReconciliationRequired("IDEMPOTENCY_KEY_INVALID")
                    }
                    else -> {
                        comandaMutationDao.markReconciliationRequiredClaimed(mutation.id, claimToken, errorCode.ifBlank { "BAD_REQUEST_400" }, "bad_request", now)
                        DispatchResult.ReconciliationRequired(errorCode.ifBlank { "BAD_REQUEST_400" })
                    }
                }
            }
            422 -> {
                if (errorCode == "IDEMPOTENCY_KEY_REUSED" || errorBody.contains("IDEMPOTENCY_KEY_REUSED", ignoreCase = true)) {
                    comandaMutationDao.markReconciliationRequiredClaimed(mutation.id, claimToken, "IDEMPOTENCY_KEY_REUSED", "idempotency_conflict", now)
                    DispatchResult.ReconciliationRequired("IDEMPOTENCY_KEY_REUSED")
                } else {
                    comandaMutationDao.markReconciliationRequiredClaimed(mutation.id, claimToken, "UNPROCESSABLE_ENTITY", "unprocessable_entity", now)
                    DispatchResult.ReconciliationRequired("UNPROCESSABLE_ENTITY")
                }
            }
            426 -> {
                comandaMutationDao.markPausedClaimed(mutation.id, claimToken, "UPDATE_REQUIRED", "update_required", now)
                DispatchResult.Paused("UPDATE_REQUIRED")
            }
            408, 429, in 500..599 -> {
                val delayMs = calculateBackoff(mutation.attemptCount)
                val reason = errorCode.ifBlank { "HTTP_$code" }
                comandaMutationDao.updateRetryClaimed(mutation.id, claimToken, now + delayMs, reason, "server_error", now)
                workScheduler.scheduleRetry(delayMs)
                DispatchResult.Retrying(delayMs, reason)
            }
            else -> {
                comandaMutationDao.markReconciliationRequiredClaimed(mutation.id, claimToken, "HTTP_$code", "unexpected_http_error", now)
                DispatchResult.ReconciliationRequired("HTTP_$code")
            }
        }
    }

    private suspend fun handleException(
        mutation: ComandaMutationEntity,
        claimToken: String,
        e: Exception,
        now: Long
    ): DispatchResult {
        Log.e(TAG, "OPEN_TABLE ${mutation.id} Falha de rede/exceção: ${e.message}", e)
        return if (e is IOException) {
            val delayMs = calculateBackoff(mutation.attemptCount)
            comandaMutationDao.updateRetryClaimed(mutation.id, claimToken, now + delayMs, "NETWORK_ERROR", "network_error", now)
            workScheduler.scheduleRetry(delayMs)
            DispatchResult.Retrying(delayMs, "NETWORK_ERROR")
        } else {
            val delayMs = 10_000L
            comandaMutationDao.updateRetryClaimed(mutation.id, claimToken, now + delayMs, "UNEXPECTED_EXCEPTION", "unexpected_error", now)
            workScheduler.scheduleRetry(delayMs)
            DispatchResult.Retrying(delayMs, "UNEXPECTED_EXCEPTION")
        }
    }

    private fun calculateBackoff(attemptCount: Int): Long {
        val exponent = min(attemptCount, 6)
        val baseDelay = 2_000L * (1L shl exponent)
        return min(baseDelay, 120_000L) // Máximo 120s de backoff
    }
}

