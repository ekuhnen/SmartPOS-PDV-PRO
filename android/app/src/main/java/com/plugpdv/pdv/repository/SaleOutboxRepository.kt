package com.plugpdv.pdv.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.LocalSaleDao
import com.plugpdv.pdv.database.LocalSaleEntity
import com.plugpdv.pdv.models.SaleRequest
import com.plugpdv.pdv.utils.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SaleOutboxRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localSaleDao: LocalSaleDao,
    private val apiService: PosApiService
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "SaleOutboxRepository"
    }

    suspend fun enqueueSale(
        saleRequest: SaleRequest,
        currency: String,
        localId: String = UUID.randomUUID().toString()
    ): LocalSaleEntity {
        val now = System.currentTimeMillis()
        val payloadJson = gson.toJson(saleRequest)
        val itemsJson = gson.toJson(saleRequest.items)

        val entity = LocalSaleEntity(
            localId = localId,
            apiId = null,
            createdAt = now,
            updatedAt = now,
            total = saleRequest.total,
            currency = currency,
            paymentMethod = saleRequest.paymentMethod,
            operatorId = saleRequest.operatorId,
            operatorName = saleRequest.operatorName,
            sessionId = saleRequest.caixa_session_id,
            itemsJson = itemsJson,
            customerName = saleRequest.customerName ?: "Consumidor Final",
            taxAmount = saleRequest.taxAmount,
            serviceFeeAmount = saleRequest.serviceFeeAmount,
            serviceFeeKind = saleRequest.serviceFeeKind,
            convertedTotal = saleRequest.convertedTotal ?: 0.0,
            payloadJson = payloadJson,
            attemptCount = 0,
            lastError = null,
            lastAttemptAt = null,
            syncStatus = LocalSaleEntity.STATUS_PENDING,
            syncedToApi = false,
            idempotencyKeyUsed = true // Vendas criadas no STABILIZE-02 nascem com idempotência ativada
        )

        localSaleDao.insert(entity)
        Log.i(TAG, "Outbox [$localId] venda enfileirada com sucesso (PENDING, Keyed)")
        return entity
    }

    suspend fun processOutboxBatch(): Int {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(Constants.TOKEN, null)

        if (token.isNullOrEmpty()) {
            Log.w(TAG, "Sem token de autenticação ativo. Sincronização da Outbox suspensa.")
            return 0
        }

        // Recuperação determinística de operações SYNCING abandonadas por crash/morte de processo.
        // SYNCING + idempotencyKeyUsed == true  -> PENDING (retry seguro com a mesma chave)
        // SYNCING + idempotencyKeyUsed == false -> UNKNOWN (nunca auto-retry para evitar duplicidade legada)
        val recoveredKeyed = localSaleDao.recoverStaleSyncingKeyedToPending()
        val recoveredUnkeyed = localSaleDao.recoverStaleSyncingUnkeyedToUnknown()

        if (recoveredKeyed > 0) {
            Log.i(TAG, "Recuperados $recoveredKeyed registros SYNCING com chave -> PENDING (Idempotent Retry).")
        }
        if (recoveredUnkeyed > 0) {
            Log.w(TAG, "Recuperados $recoveredUnkeyed registros SYNCING legados sem chave -> UNKNOWN.")
        }

        val pendingSales = localSaleDao.getPendingSales()
        if (pendingSales.isEmpty()) {
            Log.d(TAG, "Fila de outbox vazia. Nenhuma venda pendente.")
            return 0
        }

        Log.i(TAG, "Iniciando processamento de ${pendingSales.size} vendas pendentes na Outbox...")
        var processedCount = 0

        for (sale in pendingSales) {
            // Se for venda legada que NUNCA iniciou transmissão (attemptCount == 0 && lastAttemptAt == null),
            // ou se já for uma venda keyed, marcamos explicitamente a ativação da chave ANTES do POST.
            if (!sale.idempotencyKeyUsed) {
                if (sale.attemptCount == 0 && sale.lastAttemptAt == null) {
                    localSaleDao.markAsKeyed(sale.localId)
                    Log.i(TAG, "Outbox [${sale.localId}] PENDING legado nunca transmitido -> ativado idempotencyKeyUsed = 1.")
                } else {
                    Log.w(TAG, "Outbox [${sale.localId}] Venda legada tentada no passado sem chave. Ignorando envio automático.")
                    continue
                }
            }

            val marked = localSaleDao.markAsSyncing(sale.localId)
            if (marked == 0) {
                continue
            }

            Log.d(TAG, "Outbox [${sale.localId}] iniciando envio atômico idempotente (SYNCING)...")

            try {
                val request = gson.fromJson(sale.payloadJson, SaleRequest::class.java)
                val response = apiService.registerSale("Bearer $token", sale.localId, request)

                val apiIdResult = response.realId
                if (apiIdResult != null) {
                    localSaleDao.markAsSynced(sale.localId, apiIdResult)
                    Log.i(TAG, "Outbox [${sale.localId}] sincronizada com sucesso! API ID: $apiIdResult")
                    processedCount++
                } else {
                    Log.w(TAG, "Outbox [${sale.localId}] resposta com id nulo. Marcando UNKNOWN.")
                    localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_UNKNOWN, "API respondeu sem ID")
                }
            } catch (e: HttpException) {
                val code = e.code()
                val errorBody = e.response()?.errorBody()?.string() ?: e.message()
                Log.e(TAG, "Outbox [${sale.localId}] Erro HTTP $code: $errorBody")

                when {
                    code == 401 || code == 403 -> {
                        Log.w(TAG, "Outbox [${sale.localId}] Falha de autenticação ($code). Interrompendo outbox e mantendo PENDING.")
                        localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_PENDING, "Auth error HTTP $code")
                        break // Interrompe o lote para não falhar as demais por token inválido
                    }
                    code == 409 -> {
                        if (errorBody.contains("IDEMPOTENCY_KEY_REUSED") || errorBody.contains("IDEMPOTENCY_SCOPE_MISMATCH")) {
                            Log.e(TAG, "Outbox [${sale.localId}] Conflito de idempotência ($errorBody). Marcando NEEDS_RECONCILIATION.")
                            localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_NEEDS_RECONCILIATION, "HTTP 409: $errorBody")
                        } else if (errorBody.contains("OPERATION_IN_PROGRESS")) {
                            Log.w(TAG, "Outbox [${sale.localId}] Operação em andamento no servidor (HTTP 409). Mantendo PENDING e aplicando backoff (interrompendo lote).")
                            localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_PENDING, "HTTP 409: OPERATION_IN_PROGRESS")
                            break // Interrompe o lote para dar tempo ao servidor (backoff)
                        } else if (errorBody.contains("INSUFFICIENT_STOCK")) {
                            Log.e(TAG, "Outbox [${sale.localId}] Estoque insuficiente. Marcando FAILED_PERMANENT.")
                            localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_FAILED_PERMANENT, "HTTP 409: INSUFFICIENT_STOCK")
                        } else {
                            Log.w(TAG, "Outbox [${sale.localId}] HTTP 409 genérico. Marcando PENDING para retry idempotente.")
                            localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_PENDING, "HTTP 409: $errorBody")
                        }
                    }
                    code == 400 || code == 422 -> {
                        Log.e(TAG, "Outbox [${sale.localId}] Falha permanente ($code). Marcando FAILED_PERMANENT.")
                        localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_FAILED_PERMANENT, "HTTP $code: $errorBody")
                    }
                    code == 408 || code == 429 || code >= 500 -> {
                        Log.w(TAG, "Outbox [${sale.localId}] Erro temporário ($code). Mantendo PENDING para retry idempotente.")
                        localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_PENDING, "HTTP $code: $errorBody")
                    }
                    else -> {
                        Log.w(TAG, "Outbox [${sale.localId}] Erro HTTP ambíguo ($code). Marcando UNKNOWN.")
                        localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_UNKNOWN, "HTTP $code: $errorBody")
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Outbox [${sale.localId}] Exceção de E/S / Conexão (${e.message}). Mantendo PENDING para retry idempotente seguro.")
                localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_PENDING, "E/S error: ${e.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Outbox [${sale.localId}] Exceção não tratada: ${e.message}", e)
                localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_UNKNOWN, "Exception: ${e.message}")
            }
        }

        return processedCount
    }
}
