package com.plugpdv.pdv.dispatcher

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.ComandaMutationDao
import com.plugpdv.pdv.database.ComandaMutationEntity
import com.plugpdv.pdv.database.ComandaSnapshotDao
import com.plugpdv.pdv.database.TableDao
import com.plugpdv.pdv.database.TableEntity
import com.plugpdv.pdv.models.CommandActionRequest
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.TenantBindingStore
import com.plugpdv.pdv.worker.ComandaWorkScheduler
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
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

@Singleton
class ComandaOutboxDispatcher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AppDatabase,
    private val comandaMutationDao: ComandaMutationDao,
    private val comandaSnapshotDao: ComandaSnapshotDao,
    private val tableDao: TableDao,
    private val apiService: PosApiService,
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
                    // Não bloquear o loop se for isolado, mas pausar o batch se for perda de autenticação geral
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

        // 1. Tentar claim atômico
        val claimed = comandaMutationDao.claimMutation(mutation.id, claimToken, now, staleThreshold)
        if (claimed == 0) {
            return DispatchResult.Skipped("Could not claim lease; already in processing or finalized")
        }

        // 2. Validação de Auth e Ator
        if (token.isNullOrBlank()) {
            comandaMutationDao.markPaused(mutation.id, "AUTH_REQUIRED", "auth_required", now)
            return DispatchResult.Paused("AUTH_REQUIRED")
        }

        if (mutation.actorUserId != currentUserId) {
            comandaMutationDao.markPaused(mutation.id, "DIFFERENT_ACTOR", "different_actor", now)
            return DispatchResult.Paused("DIFFERENT_ACTOR")
        }

        // 3. Construção do request semântico congelado
        val payloadMap = try {
            gson.fromJson(mutation.payloadJson, Map::class.java)
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
                    database.withTransaction {
                        // Conciliação de Snapshot
                        val existingSnapshot = comandaSnapshotDao.getByLocalId(mutation.localComandaId)
                        if (existingSnapshot != null) {
                            val updatedSnapshot = existingSnapshot.copy(
                                serverComandaId = serverComandaId,
                                serverStatus = "ABERTA",
                                syncStatus = "SYNCED",
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

                        // Marcar K1 como SYNCED
                        comandaMutationDao.markSynced(mutation.id, responseNow)
                    }
                    Log.d(TAG, "OPEN_TABLE ${mutation.id} sincronizado com sucesso. serverComandaId: $serverComandaId")
                    return DispatchResult.Success(serverComandaId)
                } else {
                    Log.e(TAG, "OPEN_TABLE ${mutation.id} retornou 2xx mas com ID vazio no body")
                    comandaMutationDao.markReconciliationRequired(mutation.id, "EMPTY_SERVER_ID", "empty_server_id", responseNow)
                    return DispatchResult.ReconciliationRequired("EMPTY_SERVER_ID")
                }
            } else {
                val code = response.code()
                val errorBody = response.errorBody()?.string().orEmpty()
                return handleHttpError(mutation, code, errorBody, responseNow)
            }
        } catch (e: Exception) {
            val excNow = System.currentTimeMillis()
            return handleException(mutation, e, excNow)
        }
    }

    private suspend fun handleHttpError(
        mutation: ComandaMutationEntity,
        code: Int,
        errorBody: String,
        now: Long
    ): DispatchResult {
        Log.w(TAG, "OPEN_TABLE ${mutation.id} HTTP $code: $errorBody")
        return when (code) {
            401 -> {
                comandaMutationDao.markPaused(mutation.id, "AUTH_REQUIRED", "auth_required", now)
                DispatchResult.Paused("AUTH_REQUIRED")
            }
            403 -> {
                when {
                    errorBody.contains("OPERATION_MODE_DISABLED", ignoreCase = true) -> {
                        comandaMutationDao.markReconciliationRequired(mutation.id, "OPERATION_MODE_DISABLED", "mode_disabled", now)
                        DispatchResult.ReconciliationRequired("OPERATION_MODE_DISABLED")
                    }
                    errorBody.contains("DEVICE_BLOCKED", ignoreCase = true) -> {
                        comandaMutationDao.markPaused(mutation.id, "DEVICE_BLOCKED", "device_blocked", now)
                        DispatchResult.Paused("DEVICE_BLOCKED")
                    }
                    else -> {
                        comandaMutationDao.markReconciliationRequired(mutation.id, "FORBIDDEN", "forbidden", now)
                        DispatchResult.ReconciliationRequired("FORBIDDEN")
                    }
                }
            }
            409 -> {
                comandaMutationDao.markReconciliationRequired(mutation.id, "TABLE_ALREADY_OCCUPIED", "table_conflict", now)
                DispatchResult.ReconciliationRequired("TABLE_ALREADY_OCCUPIED")
            }
            422 -> {
                if (errorBody.contains("IDEMPOTENCY_KEY_REUSED", ignoreCase = true)) {
                    comandaMutationDao.markReconciliationRequired(mutation.id, "IDEMPOTENCY_KEY_REUSED", "idempotency_conflict", now)
                    DispatchResult.ReconciliationRequired("IDEMPOTENCY_KEY_REUSED")
                } else {
                    comandaMutationDao.markReconciliationRequired(mutation.id, "UNPROCESSABLE_ENTITY", "unprocessable_entity", now)
                    DispatchResult.ReconciliationRequired("UNPROCESSABLE_ENTITY")
                }
            }
            426 -> {
                comandaMutationDao.markPaused(mutation.id, "UPDATE_REQUIRED", "update_required", now)
                DispatchResult.Paused("UPDATE_REQUIRED")
            }
            408, 429, in 500..599 -> {
                val delayMs = calculateBackoff(mutation.attemptCount)
                comandaMutationDao.updateRetry(mutation.id, now + delayMs, "HTTP_$code", "server_error", now)
                workScheduler.scheduleRetry(delayMs)
                DispatchResult.Retrying(delayMs, "HTTP_$code")
            }
            else -> {
                comandaMutationDao.markReconciliationRequired(mutation.id, "HTTP_$code", "unexpected_http_error", now)
                DispatchResult.ReconciliationRequired("HTTP_$code")
            }
        }
    }

    private suspend fun handleException(
        mutation: ComandaMutationEntity,
        e: Exception,
        now: Long
    ): DispatchResult {
        Log.e(TAG, "OPEN_TABLE ${mutation.id} Falha de rede/exceção: ${e.message}", e)
        return if (e is IOException) {
            val delayMs = calculateBackoff(mutation.attemptCount)
            comandaMutationDao.updateRetry(mutation.id, now + delayMs, "NETWORK_ERROR", "network_error", now)
            workScheduler.scheduleRetry(delayMs)
            DispatchResult.Retrying(delayMs, "NETWORK_ERROR")
        } else {
            val delayMs = 10_000L
            comandaMutationDao.updateRetry(mutation.id, now + delayMs, "UNEXPECTED_EXCEPTION", "unexpected_error", now)
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
