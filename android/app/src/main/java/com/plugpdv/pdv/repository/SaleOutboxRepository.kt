package com.plugpdv.pdv.repository

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.AppDatabase
import com.plugpdv.pdv.database.LocalSaleDao
import com.plugpdv.pdv.database.LocalSaleEntity
import com.plugpdv.pdv.database.PaymentAttemptDao
import com.plugpdv.pdv.database.PaymentAttemptEntity
import com.plugpdv.pdv.models.SaleRequest
import com.plugpdv.pdv.utils.Constants
import dagger.hilt.android.qualifiers.ApplicationContext
import retrofit2.HttpException
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SaleDrainResult(
    val processedCount: Int,
    val remainingCount: Int,
    val stopReason: StopReason
)

enum class StopReason {
    EMPTY,
    PROGRESSED,
    AUTH_REQUIRED,
    BACKOFF_REQUIRED,
    TRANSIENT_FAILURE,
    PERMANENT_ONLY,
    NO_PROGRESS
}

@Singleton
class SaleOutboxRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localSaleDao: LocalSaleDao,
    private val apiService: PosApiService? = null,
    private val appDatabase: AppDatabase,
    private val paymentAttemptDao: PaymentAttemptDao
) {
    private val gson = Gson()

    companion object {
        private const val TAG = "SaleOutboxRepository"
        var faultInjectionHook: String? = null
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
            total = saleRequest.total.toDouble(),
            currency = currency,
            paymentMethod = saleRequest.paymentMethod,
            operatorId = saleRequest.operatorId,
            operatorName = saleRequest.operatorName,
            sessionId = saleRequest.caixa_session_id,
            itemsJson = itemsJson,
            customerName = saleRequest.customerName ?: "Consumidor Final",
            taxAmount = saleRequest.taxAmount.toDouble(),
            serviceFeeAmount = saleRequest.serviceFeeAmount.toDouble(),
            serviceFeeKind = saleRequest.serviceFeeKind,
            convertedTotal = saleRequest.convertedTotal?.toDouble() ?: 0.0,
            payloadJson = payloadJson,
            attemptCount = 0,
            lastError = null,
            lastAttemptAt = null,
            syncStatus = LocalSaleEntity.STATUS_PENDING,
            syncedToApi = false,
            idempotencyKeyUsed = true
        )

        localSaleDao.insert(entity)
        Log.i(TAG, "Outbox [$localId] venda enfileirada com sucesso (PENDING, Keyed)")
        return entity
    }

    suspend fun prepareDirectSaleAtomic(
        saleRequest: SaleRequest,
        currency: String,
        localId: String,
        minimalUnitAmount: Long,
        orderId: String? = null,
        description: String? = "Venda Direta - PDV"
    ): LocalSaleEntity {
        return appDatabase.withTransaction {
            val now = System.currentTimeMillis()
            val payloadJson = gson.toJson(saleRequest)
            val itemsJson = gson.toJson(saleRequest.items)

            val localSale = LocalSaleEntity(
                localId = localId,
                apiId = null,
                createdAt = now,
                updatedAt = now,
                total = saleRequest.total.toDouble(),
                currency = currency,
                paymentMethod = saleRequest.paymentMethod,
                operatorId = saleRequest.operatorId,
                operatorName = saleRequest.operatorName,
                sessionId = saleRequest.caixa_session_id,
                itemsJson = itemsJson,
                customerName = saleRequest.customerName ?: "Consumidor Final",
                taxAmount = saleRequest.taxAmount.toDouble(),
                serviceFeeAmount = saleRequest.serviceFeeAmount.toDouble(),
                serviceFeeKind = saleRequest.serviceFeeKind,
                convertedTotal = saleRequest.convertedTotal?.toDouble() ?: 0.0,
                payloadJson = payloadJson,
                attemptCount = 0,
                lastError = null,
                lastAttemptAt = null,
                syncStatus = LocalSaleEntity.STATUS_WAITING_PAYMENT,
                syncedToApi = false,
                idempotencyKeyUsed = true
            )
            localSaleDao.insert(localSale)

            val attempt = PaymentAttemptEntity(
                reference = localId,
                idempotencyKey = localId,
                nonce = UUID.randomUUID().toString(),
                amount = minimalUnitAmount,
                currency = saleRequest.paymentCurrency ?: currency,
                status = PaymentAttemptEntity.STATUS_PREPARED,
                startedAt = now,
                orderId = orderId,
                description = description
            )
            paymentAttemptDao.insert(attempt)
            Log.i(TAG, "DirectSale [$localId] persistida atomicamente como WAITING_PAYMENT + PaymentAttempt PREPARED no Room")
            localSale
        }
    }

    suspend fun finalizeApprovedSaleAtomic(
        localId: String,
        paymentId: String?,
        method: String?
    ): LocalSaleEntity? {
        return appDatabase.withTransaction {
            val sale = localSaleDao.getById(localId) ?: return@withTransaction null
            val now = System.currentTimeMillis()

            val attempt = paymentAttemptDao.getByReference(localId)
            if (attempt != null) {
                val updatedAttempt = attempt.copy(
                    status = PaymentAttemptEntity.STATUS_APPROVED,
                    completedAt = now,
                    paymentAppPaymentId = paymentId ?: attempt.paymentAppPaymentId,
                    paymentMethod = method ?: attempt.paymentMethod
                )
                paymentAttemptDao.update(updatedAttempt)
            }

            val updatedSale = sale.copy(
                syncStatus = LocalSaleEntity.STATUS_PENDING,
                updatedAt = now,
                paymentMethod = method ?: sale.paymentMethod
            )
            localSaleDao.update(updatedSale)
            Log.i(TAG, "DirectSale [$localId] promovida atomicamente para PENDING no Room com PaymentAttempt APPROVED")
            updatedSale
        }
    }

    suspend fun recoverApprovedWaitingSalesAtomic(): Int {
        return appDatabase.withTransaction {
            val waitingSales = localSaleDao.getWaitingPaymentSales()
            var recovered = 0
            val now = System.currentTimeMillis()
            for (sale in waitingSales) {
                val attempt = paymentAttemptDao.getByReference(sale.localId)
                if (attempt?.status == PaymentAttemptEntity.STATUS_APPROVED) {
                    localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_PENDING, null, now)
                    recovered++
                    Log.i(TAG, "Recuperada venda direta órfã [${sale.localId}] WAITING_PAYMENT com PaymentAttempt APPROVED -> PENDING")
                }
            }
            recovered
        }
    }

    suspend fun processOutboxBatch(): SaleDrainResult {
        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(Constants.TOKEN, null)

        val pendingBefore = localSaleDao.getPendingSales()

        if (token.isNullOrEmpty()) {
            Log.w(TAG, "Sem token de autenticação ativo. Sincronização da Outbox suspensa.")
            return SaleDrainResult(0, pendingBefore.size, StopReason.AUTH_REQUIRED)
        }

        val recoveredWaiting = recoverApprovedWaitingSalesAtomic()
        val recoveredKeyed = localSaleDao.recoverStaleSyncingKeyedToPending()
        val recoveredUnkeyed = localSaleDao.recoverStaleSyncingUnkeyedToUnknown()

        if (recoveredWaiting > 0) {
            Log.i(TAG, "Recuperados $recoveredWaiting registros WAITING_PAYMENT com pagamento aprovado -> PENDING.")
        }
        if (recoveredKeyed > 0) {
            Log.i(TAG, "Recuperados $recoveredKeyed registros SYNCING com chave -> PENDING (Idempotent Retry).")
        }
        if (recoveredUnkeyed > 0) {
            Log.w(TAG, "Recuperados $recoveredUnkeyed registros SYNCING legados sem chave -> UNKNOWN.")
        }

        val pendingSales = localSaleDao.getPendingSales()
        if (pendingSales.isEmpty()) {
            Log.d(TAG, "Fila de outbox vazia. Nenhuma venda pendente.")
            return SaleDrainResult(0, 0, StopReason.EMPTY)
        }

        Log.i(TAG, "Iniciando processamento de ${pendingSales.size} vendas pendentes na Outbox...")
        var processedCount = 0
        var stopReason = StopReason.PROGRESSED

        for (sale in pendingSales) {
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
                val response = apiService?.registerSale("Bearer $token", sale.localId, request)
                    ?: throw java.io.IOException("PosApiService is null in SaleOutboxRepository")

                if (faultInjectionHook == "AFTER_HTTP_BEFORE_ROOM_SUCCESS") {
                    faultInjectionHook = null
                    throw java.io.IOException("FaultInjection: Killed after HTTP success before Room update")
                }

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
                        stopReason = StopReason.AUTH_REQUIRED
                        break
                    }
                    code == 409 -> {
                        if (errorBody.contains("IDEMPOTENCY_KEY_REUSED") || errorBody.contains("IDEMPOTENCY_SCOPE_MISMATCH")) {
                            Log.e(TAG, "Outbox [${sale.localId}] Conflito de idempotência ($errorBody). Marcando NEEDS_RECONCILIATION.")
                            localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_NEEDS_RECONCILIATION, "HTTP 409: $errorBody")
                        } else if (errorBody.contains("OPERATION_IN_PROGRESS")) {
                            Log.w(TAG, "Outbox [${sale.localId}] Operação em andamento no servidor (HTTP 409). Mantendo PENDING e aplicando backoff (interrompendo lote).")
                            localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_PENDING, "HTTP 409: OPERATION_IN_PROGRESS")
                            stopReason = StopReason.BACKOFF_REQUIRED
                            break
                        } else if (errorBody.contains("INSUFFICIENT_STOCK")) {
                            Log.e(TAG, "Outbox [${sale.localId}] Estoque insuficiente. Marcando FAILED_PERMANENT.")
                            localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_FAILED_PERMANENT, "HTTP 409: INSUFFICIENT_STOCK")
                        } else {
                            Log.w(TAG, "Outbox [${sale.localId}] HTTP 409 genérico. Marcando PENDING para retry idempotente.")
                            localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_PENDING, "HTTP 409: $errorBody")
                            stopReason = StopReason.TRANSIENT_FAILURE
                            break
                        }
                    }
                    code == 400 || code == 422 -> {
                        Log.e(TAG, "Outbox [${sale.localId}] Falha permanente ($code). Marcando FAILED_PERMANENT.")
                        localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_FAILED_PERMANENT, "HTTP $code: $errorBody")
                    }
                    code == 408 || code == 429 || code >= 500 -> {
                        Log.w(TAG, "Outbox [${sale.localId}] Erro temporário ($code). Mantendo PENDING para retry idempotente.")
                        localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_PENDING, "HTTP $code: $errorBody")
                        stopReason = StopReason.TRANSIENT_FAILURE
                        break
                    }
                    else -> {
                        Log.w(TAG, "Outbox [${sale.localId}] Erro HTTP ambíguo ($code). Marcando UNKNOWN.")
                        localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_UNKNOWN, "HTTP $code: $errorBody")
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Outbox [${sale.localId}] Exceção de E/S / Conexão (${e.message}). Mantendo PENDING para retry idempotente seguro.")
                localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_PENDING, "E/S error: ${e.message}")
                stopReason = StopReason.TRANSIENT_FAILURE
                break
            } catch (e: Exception) {
                Log.e(TAG, "Outbox [${sale.localId}] Exceção não tratada: ${e.message}", e)
                localSaleDao.markAsStatus(sale.localId, LocalSaleEntity.STATUS_UNKNOWN, "Exception: ${e.message}")
            }
        }

        val remaining = localSaleDao.getPendingSales().size
        val finalStopReason = if (remaining == 0) {
            StopReason.EMPTY
        } else if (processedCount == 0 && stopReason == StopReason.PROGRESSED) {
            StopReason.NO_PROGRESS
        } else {
            stopReason
        }

        return SaleDrainResult(processedCount, remaining, finalStopReason)
    }
}
