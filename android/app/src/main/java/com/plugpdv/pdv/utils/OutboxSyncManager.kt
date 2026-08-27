package com.plugpdv.pdv.utils

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.plugpdv.pdv.api.PosApiService
import com.plugpdv.pdv.database.OutboxDao
import com.plugpdv.pdv.database.OutboxOperationEntity
import com.plugpdv.pdv.models.CommandActionRequest
import com.plugpdv.pdv.models.SaleRequest
import com.plugpdv.pdv.models.SyncBatchRequest
import com.plugpdv.pdv.models.SyncOperationItem
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min
import kotlin.math.pow

enum class SingleOperationResult {
    SYNCED,
    RETRY,
    FAILED_PERMANENT,
    NEEDS_RECONCILIATION
}

data class CheckoutResultEvent(
    val operationId: String,
    val comandaId: String,
    val mesaId: String?,
    val closed: Boolean,
    val requiresReconciliation: Boolean,
    val remainingBalance: Double = 0.0
)

data class OutboxQueueStatus(
    val pendingCount: Int = 0,
    val oldestPendingAgeMs: Long = 0L,
    val hasCriticalQueue: Boolean = false, // > 20 itens ou > 5 minutos de atraso
    val alertMessage: String? = null
)

@Singleton
class OutboxSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val outboxDao: OutboxDao,
    private val paymentAttemptDao: com.plugpdv.pdv.database.PaymentAttemptDao,
    private val apiService: PosApiService,
    private val syncMetricsTracker: SyncMetricsTracker,
    private val saleSyncScheduler: com.plugpdv.pdv.outbox.SaleSyncScheduler
) {
    private val gson: Gson = Gson()

    companion object {
        private const val TAG = "OutboxSyncManager"
        const val CRITICAL_QUEUE_COUNT_THRESHOLD = 20
        const val CRITICAL_QUEUE_DELAY_THRESHOLD_MS = 5 * 60 * 1000L // 5 minutos
        const val MAX_BACKOFF_SECONDS = 60L
        var faultInjectionHook: String? = null
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val groupMutexes = ConcurrentHashMap<String, Mutex>()
    private var syncJob: Job? = null

    private val _checkoutResultEvents = MutableSharedFlow<CheckoutResultEvent>(replay = 1, extraBufferCapacity = 64)
    val checkoutResultEvents: SharedFlow<CheckoutResultEvent> = _checkoutResultEvents.asSharedFlow()

    private val _queueStatus = MutableStateFlow(OutboxQueueStatus())
    val queueStatus: StateFlow<OutboxQueueStatus> = _queueStatus.asStateFlow()

    init {
        startObservingQueue()
        startPeriodicSync()
    }

    private fun startObservingQueue() {
        scope.launch {
            combine(
                outboxDao.getPendingCountFlow(),
                outboxDao.getOldestPendingTimestampFlow()
            ) { count, oldestTimestamp ->
                val now = System.currentTimeMillis()
                val ageMs = if (oldestTimestamp != null && oldestTimestamp > 0) {
                    (now - oldestTimestamp).coerceAtLeast(0L)
                } else {
                    0L
                }

                val isCountExceeded = count >= CRITICAL_QUEUE_COUNT_THRESHOLD
                val isDelayExceeded = ageMs >= CRITICAL_QUEUE_DELAY_THRESHOLD_MS && count > 0

                val isCritical = isCountExceeded || isDelayExceeded
                val message = when {
                    isCountExceeded && isDelayExceeded -> "Fila de sincronização com $count operações pendentes há mais de ${ageMs / 60000} minutos."
                    isCountExceeded -> "Fila de sincronização cheia ($count operações pendentes)."
                    isDelayExceeded -> "Operações aguardando sincronização há mais de ${ageMs / 60000} minutos."
                    else -> null
                }

                OutboxQueueStatus(
                    pendingCount = count,
                    oldestPendingAgeMs = ageMs,
                    hasCriticalQueue = isCritical,
                    alertMessage = message
                )
            }.collect { status ->
                _queueStatus.value = status
            }
        }
    }

    fun enqueue(
        id: String,
        operationType: String,
        targetGroupKey: String,
        payloadJson: String,
        idempotencyKey: String = id
    ) {
        scope.launch {
            val now = System.currentTimeMillis()
            val entity = OutboxOperationEntity(
                id = id,
                operationType = operationType,
                targetGroupKey = targetGroupKey,
                payloadJson = payloadJson,
                createdAt = now,
                idempotencyKey = idempotencyKey,
                nextRetryAt = now,
                status = "PENDING"
            )
            outboxDao.insert(entity)
            Log.d(TAG, "Operação $id ($operationType) enfileirada na Outbox para o grupo $targetGroupKey com idempotencyKey=$idempotencyKey")
            triggerSync()
        }
    }

    fun triggerSync() {
        scope.launch {
            processPendingOutbox()
        }
    }

    private fun startPeriodicSync() {
        syncJob?.cancel()
        syncJob = scope.launch {
            while (isActive) {
                try {
                    processPendingOutbox()
                } catch (e: Exception) {
                    Log.e(TAG, "Erro no loop de sincronização periódica: ${e.message}")
                }
                delay(10_000) // Verifica a cada 10 segundos
            }
        }
    }

    suspend fun recoverAndPromoteApprovedCheckouts() {
        try {
            val now = System.currentTimeMillis()
            val fiveMinutesMs = 5 * 60 * 1000L

            // 1. Recuperar PaymentAttempts PENDING antigas (> 5 min) -> UNKNOWN (nunca REJECTED)
            val pendingAttempts = paymentAttemptDao.getPendingOrUndeterminedAttempts().filter { it.status == "PENDING" }
            for (attempt in pendingAttempts) {
                if (now - attempt.startedAt > fiveMinutesMs) {
                    paymentAttemptDao.update(
                        attempt.copy(
                            status = "UNKNOWN",
                            statusMessage = "Timeout local de callback (> 5 min). Necessita verificação."
                        )
                    )
                    Log.w(TAG, "Tentativa K=${attempt.reference} PENDING há mais de 5min -> UNKNOWN / NEEDS_VERIFICATION.")
                }
            }

            val approvedAttempts = paymentAttemptDao.getApprovedAttempts()
            val waitingOps = outboxDao.getWaitingPaymentOperations()

            // Caso A & F: Promover WAITING_PAYMENT com PaymentAttempt APPROVED para PENDING / Terminalizar CANCELLED/REJECTED
            for (op in waitingOps) {
                val matchingAttempt = paymentAttemptDao.getByReference(op.idempotencyKey)
                    ?: approvedAttempts.find { it.reference == op.idempotencyKey || it.idempotencyKey == op.idempotencyKey }

                if (matchingAttempt != null) {
                    when (matchingAttempt.status) {
                        "APPROVED" -> {
                            val request = gson.fromJson(op.payloadJson, com.plugpdv.pdv.models.CommandCheckoutCommitRequest::class.java)
                            val updatedRequest = request.copy(
                                referenciaExterna = matchingAttempt.paymentAppPaymentId ?: request.referenciaExterna,
                                forma = matchingAttempt.paymentMethod ?: request.forma
                            )

                            val updatedOp = op.copy(
                                payloadJson = gson.toJson(updatedRequest),
                                status = "PENDING"
                            )

                            outboxDao.update(updatedOp)
                            Log.i(TAG, "Process Death Recovery (Caso A): Operação K=${op.idempotencyKey} promovida para PENDING (PaymentAttempt APPROVED)")
                        }
                        "CANCELLED", "REJECTED" -> {
                            outboxDao.markAsFailedWithKey(op.id, matchingAttempt.status, "CANCELLED_PAYMENT", false)
                            Log.i(TAG, "Process Death Recovery (Caso F): Outbox K=${op.idempotencyKey} terminalizada como FAILED/CANCELLED_PAYMENT.")
                        }
                        "UNKNOWN" -> {
                            // Caso C: PaymentAttempt UNKNOWN + Outbox WAITING -> NEEDS_VERIFICATION (não faz checkout backend, não apaga)
                            Log.w(TAG, "Caso C: Outbox K=${op.idempotencyKey} vinculada a PaymentAttempt UNKNOWN. Mantida para verificação do operador.")
                        }
                    }
                } else if (now - op.createdAt > fiveMinutesMs) {
                    // Caso D: Outbox WAITING_PAYMENT sem PaymentAttempt (>5min) -> FAILED sem apagar
                    outboxDao.markAsFailedWithKey(op.id, "ORPHAN_WAITING_TIMEOUT", "ORPHAN_WAITING", false)
                    Log.w(TAG, "Caso D: Outbox K=${op.idempotencyKey} WAITING_PAYMENT sem PaymentAttempt (>5min) -> FAILED.")
                }
            }

            // Caso B: PaymentAttempt APPROVED sem Outbox correspondente
            for (attempt in approvedAttempts) {
                val op = outboxDao.getById(attempt.reference)
                if (op == null && attempt.orderId != null && attempt.orderId != "0") {
                    Log.w(TAG, "Caso B: PaymentAttempt APPROVED sem Outbox. Reference: ${attempt.reference}. Preservado no Room.")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha durante recuperação de process death para checkout: ${e.message}")
        }
    }

    suspend fun drainPendingOutbox(): Boolean {
        recoverAndPromoteApprovedCheckouts()
        val now = System.currentTimeMillis()
        val groups = outboxDao.getDistinctPendingGroups(now)
        if (groups.isEmpty()) return true

        val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        val token = prefs.getString(Constants.TOKEN, null) ?: return false

        val jobs = groups.map { groupKey ->
            coroutineScope {
                async {
                    val groupMutex = groupMutexes.getOrPut(groupKey) { Mutex() }
                    groupMutex.withLock {
                        processGroupQueue(groupKey, token)
                    }
                }
            }
        }
        jobs.awaitAll()
        return true
    }

    private suspend fun processPendingOutbox() {
        drainPendingOutbox()
    }

    private suspend fun processGroupQueue(groupKey: String, token: String) {
        val operations = outboxDao.getPendingForGroup(groupKey)
        if (operations.isEmpty()) return

        val readyOps = operations.takeWhile { it.nextRetryAt <= System.currentTimeMillis() }
            .take(50)

        if (readyOps.isEmpty()) return

        // Excluir COMANDA_CHECKOUT_COMMIT do sync_batch para envio individual idempotente dedicado
        val (checkoutOps, batchOps) = readyOps.partition { it.operationType == "COMANDA_CHECKOUT_COMMIT" }

        // Processar operações de checkout atômico individualmente
        for (op in checkoutOps) {
            val result = executeSingleOperation(op, token)
            when (result) {
                SingleOperationResult.SYNCED -> {
                    outboxDao.markAsSynced(op.id)
                    syncMetricsTracker.recordSyncFlush(
                        groupOrSaleId = op.targetGroupKey,
                        createdAt = op.createdAt
                    )
                    Log.d(TAG, "Operação de checkout K=${op.idempotencyKey} sincronizada com sucesso.")
                }
                SingleOperationResult.RETRY -> {
                    val nextAttempt = op.attemptCount + 1
                    val backoffSec = min(2.0.pow(nextAttempt.toDouble()).toLong(), MAX_BACKOFF_SECONDS)
                    val nextRetry = System.currentTimeMillis() + (backoffSec * 1000L)
                    outboxDao.update(op.copy(
                        attemptCount = nextAttempt,
                        lastAttemptAt = System.currentTimeMillis(),
                        nextRetryAt = nextRetry,
                        status = "PENDING"
                    ))
                    saleSyncScheduler.scheduleRetry(context, backoffSec * 1000L)
                    Log.w(TAG, "Operação de checkout K=${op.idempotencyKey} falhou no envio (tentativa $nextAttempt). Reagendando em ${backoffSec}s.")
                }
                SingleOperationResult.FAILED_PERMANENT -> {
                    Log.e(TAG, "Operação de checkout K=${op.idempotencyKey} falhou permanentemente.")
                }
                SingleOperationResult.NEEDS_RECONCILIATION -> {
                    Log.w(TAG, "Operação de checkout K=${op.idempotencyKey} em estado de reconciliação.")
                }
            }
        }

        if (batchOps.isNotEmpty()) {
            val batchSynced = try {
                val items = batchOps.map { op ->
                    SyncOperationItem(
                        id = op.id,
                        operationType = op.operationType,
                        targetGroupKey = op.targetGroupKey,
                        idempotencyKey = op.idempotencyKey,
                        clientCreatedAt = op.createdAt,
                        payloadJson = op.payloadJson
                    )
                }
                val response = apiService.syncBatch("Bearer $token", SyncBatchRequest(items))
                if (response.isSuccessful && response.body() != null) {
                    val results = response.body()!!.results
                    for (res in results) {
                        val op = batchOps.find { it.id == res.operationId } ?: continue
                        if (res.success) {
                            outboxDao.markAsSyncedWithSeq(op.id, res.serverSeq)
                            syncMetricsTracker.recordSyncFlush(
                                groupOrSaleId = op.targetGroupKey,
                                createdAt = op.createdAt
                            )
                            Log.d(TAG, "Operação ${op.id} sincronizada em lote com sucesso (serverSeq=${res.serverSeq}).")
                        } else {
                            if (res.retriable) {
                                val nextAttempt = op.attemptCount + 1
                                val backoffSec = min(2.0.pow(nextAttempt.toDouble()).toLong(), MAX_BACKOFF_SECONDS)
                                val nextRetry = System.currentTimeMillis() + (backoffSec * 1000L)
                                outboxDao.update(op.copy(
                                    attemptCount = nextAttempt,
                                    lastAttemptAt = System.currentTimeMillis(),
                                    nextRetryAt = nextRetry,
                                    lastError = res.errorCode,
                                    messageKey = res.messageKey,
                                    status = "PENDING"
                                ))
                            } else {
                                outboxDao.markAsFailedWithKey(
                                    id = op.id,
                                    error = res.errorCode ?: "UNRECOVERABLE_ERROR",
                                    messageKey = res.messageKey,
                                    isRetriable = false
                                )
                            }
                        }
                    }
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                Log.w(TAG, "sync_batch falhou (${e.message}). Executando fallback individual.")
                false
            }

            if (!batchSynced) {
                for (op in batchOps) {
                    val result = executeSingleOperation(op, token)
                    when (result) {
                        SingleOperationResult.SYNCED -> {
                            outboxDao.markAsSynced(op.id)
                            syncMetricsTracker.recordSyncFlush(
                                groupOrSaleId = op.targetGroupKey,
                                createdAt = op.createdAt
                            )
                        }
                        SingleOperationResult.RETRY -> {
                            val nextAttempt = op.attemptCount + 1
                            val backoffSec = min(2.0.pow(nextAttempt.toDouble()).toLong(), MAX_BACKOFF_SECONDS)
                            val nextRetry = System.currentTimeMillis() + (backoffSec * 1000L)
                            outboxDao.update(op.copy(
                                attemptCount = nextAttempt,
                                lastAttemptAt = System.currentTimeMillis(),
                                nextRetryAt = nextRetry,
                                status = "PENDING"
                            ))
                            saleSyncScheduler.scheduleRetry(context, backoffSec * 1000L)
                            break
                        }
                        SingleOperationResult.FAILED_PERMANENT, SingleOperationResult.NEEDS_RECONCILIATION -> {
                            // Status já registrado no DAO
                        }
                    }
                }
            }
        }
    }

    private suspend fun executeSingleOperation(op: OutboxOperationEntity, token: String): SingleOperationResult {
        return try {
            when (op.operationType) {
                "COMMAND_ACTION" -> {
                    val request = gson.fromJson(op.payloadJson, CommandActionRequest::class.java)
                    val response = apiService.manageComanda("Bearer $token", request, op.idempotencyKey)
                    if (response.isSuccessful) {
                        SingleOperationResult.SYNCED
                    } else if (response.code() in 400..422 && response.code() != 408 && response.code() != 409) {
                        outboxDao.markAsFailedWithKey(op.id, "HTTP_${response.code()}", "UNRECOVERABLE_ERROR", false)
                        SingleOperationResult.FAILED_PERMANENT
                    } else {
                        SingleOperationResult.RETRY
                    }
                }
                "COMANDA_CHECKOUT_COMMIT" -> {
                    val request = gson.fromJson(op.payloadJson, com.plugpdv.pdv.models.CommandCheckoutCommitRequest::class.java)

                    val isCash = request.forma.equals("DINHEIRO", ignoreCase = true) ||
                                 request.forma.equals("CASH", ignoreCase = true) ||
                                 request.forma.equals("MONEY", ignoreCase = true)

                    if (!isCash) {
                        val matchingAttempt = paymentAttemptDao.getByReference(op.idempotencyKey)
                        val hasApprovedAttempt = matchingAttempt?.status == "APPROVED"
                        val hasValidExternalRef = !request.referenciaExterna.isNullOrEmpty()

                        if (!hasApprovedAttempt && !hasValidExternalRef) {
                            Log.e(TAG, "Tentativa de checkout sem aprovação comprovada do pagamento externo (K=${op.idempotencyKey}). Bloqueando envio.")
                            outboxDao.markAsFailedWithKey(op.id, "MISSING_PAYMENT_APPROVAL", "REQUIRES_RECONCILIATION", false)
                            _checkoutResultEvents.emit(
                                CheckoutResultEvent(
                                    operationId = op.id,
                                    comandaId = op.targetGroupKey,
                                    mesaId = request.mesaId,
                                    closed = false,
                                    requiresReconciliation = true
                                )
                            )
                            return SingleOperationResult.NEEDS_RECONCILIATION
                        }
                    }

                    val response = apiService.commitComandaCheckout("Bearer $token", op.idempotencyKey, request)
                    val statusCode = response.code()

                    if (response.isSuccessful && response.body()?.success == true) {
                        if (faultInjectionHook == "AFTER_HTTP_BEFORE_ROOM_SUCCESS") {
                            faultInjectionHook = null
                            throw java.io.IOException("FaultInjection: Killed after HTTP success before Room update")
                        }

                        val commitRes = response.body()
                        if (commitRes?.requiresReconciliation == true) {
                            Log.w(TAG, "Operação de checkout K=${op.idempotencyKey} requer conciliação financeira.")
                            outboxDao.markAsFailedWithKey(op.id, "REQUIRES_RECONCILIATION", "REQUIRES_RECONCILIATION", false)
                            _checkoutResultEvents.emit(
                                CheckoutResultEvent(
                                    operationId = op.id,
                                    comandaId = op.targetGroupKey,
                                    mesaId = commitRes.mesaId ?: request.mesaId,
                                    closed = commitRes.closed,
                                    requiresReconciliation = true,
                                    remainingBalance = commitRes.remainingBalance
                                )
                            )
                            SingleOperationResult.NEEDS_RECONCILIATION
                        } else {
                            if (commitRes?.closed == true || commitRes?.comandaStatus.equals("FECHADA", ignoreCase = true)) {
                                val mesaId = commitRes?.mesaId ?: request.mesaId
                                if (!mesaId.isNullOrEmpty()) {
                                    com.plugpdv.pdv.utils.TableManager.markTableAvailable(mesaId)
                                }
                            }
                            _checkoutResultEvents.emit(
                                CheckoutResultEvent(
                                    operationId = op.id,
                                    comandaId = op.targetGroupKey,
                                    mesaId = commitRes?.mesaId ?: request.mesaId,
                                    closed = commitRes?.closed == true || commitRes?.comandaStatus.equals("FECHADA", ignoreCase = true),
                                    requiresReconciliation = false,
                                    remainingBalance = commitRes?.remainingBalance ?: 0.0
                                )
                            )
                            SingleOperationResult.SYNCED
                        }
                    } else if (statusCode == 409) {
                        val errorBodyStr = response.errorBody()?.string() ?: ""
                        val errorCode = try {
                            val jsonObj = gson.fromJson(errorBodyStr, com.google.gson.JsonObject::class.java)
                            jsonObj?.get("code")?.asString ?: jsonObj?.get("error")?.asString ?: ""
                        } catch (e: Exception) { "" }

                        when (errorCode) {
                            "OPERATION_IN_PROGRESS" -> {
                                Log.w(TAG, "Operação K=${op.idempotencyKey} em progresso no servidor (OPERATION_IN_PROGRESS). Reagendando backoff.")
                                SingleOperationResult.RETRY
                            }
                            "IDEMPOTENCY_KEY_REUSED", "IDEMPOTENCY_SCOPE_MISMATCH" -> {
                                Log.e(TAG, "Operação K=${op.idempotencyKey} conflito de chave ($errorCode). Marcando NEEDS_RECONCILIATION sem retry.")
                                outboxDao.markAsFailedWithKey(op.id, errorCode, "REQUIRES_RECONCILIATION", false)
                                _checkoutResultEvents.emit(
                                    CheckoutResultEvent(
                                        operationId = op.id,
                                        comandaId = op.targetGroupKey,
                                        mesaId = request.mesaId,
                                        closed = false,
                                        requiresReconciliation = true
                                    )
                                )
                                SingleOperationResult.NEEDS_RECONCILIATION
                            }
                            "INSUFFICIENT_STOCK", "COMANDA_ALREADY_CLOSED" -> {
                                Log.e(TAG, "Operação K=${op.idempotencyKey} falhou por regra de negócio ($errorCode). Cancelando retries.")
                                outboxDao.markAsFailedWithKey(op.id, errorCode, "BUSINESS_RULE_ERROR", false)
                                SingleOperationResult.FAILED_PERMANENT
                            }
                            else -> {
                                Log.e(TAG, "Operação K=${op.idempotencyKey} HTTP 409 não classificado ($errorCode). Cancelando retries.")
                                outboxDao.markAsFailedWithKey(op.id, if (errorCode.isNotEmpty()) errorCode else "HTTP_409", "UNRECOVERABLE_ERROR", false)
                                SingleOperationResult.FAILED_PERMANENT
                            }
                        }
                    } else if (statusCode == 400 || statusCode == 422) {
                        Log.e(TAG, "Operação K=${op.idempotencyKey} falhou com erro permanente (HTTP $statusCode). Cancelando retries.")
                        outboxDao.markAsFailedWithKey(op.id, "HTTP_$statusCode", "UNRECOVERABLE_ERROR", false)
                        SingleOperationResult.FAILED_PERMANENT
                    } else if (statusCode == 401 || statusCode == 403) {
                        Log.w(TAG, "Operação K=${op.idempotencyKey} aguardando re-autenticação (HTTP $statusCode).")
                        SingleOperationResult.RETRY
                    } else {
                        SingleOperationResult.RETRY
                    }
                }
                "SALE_DIRECT" -> {
                    val request = gson.fromJson(op.payloadJson, SaleRequest::class.java)
                    val response = apiService.registerSale("Bearer $token", op.idempotencyKey, request)
                    if (response.id != null) {
                        SingleOperationResult.SYNCED
                    } else {
                        SingleOperationResult.RETRY
                    }
                }
                else -> {
                    Log.e(TAG, "Tipo de operação desconhecido na outbox: ${op.operationType}")
                    outboxDao.markAsFailedWithKey(op.id, "UNKNOWN_OPERATION", "UNRECOVERABLE_ERROR", false)
                    SingleOperationResult.FAILED_PERMANENT
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exceção ao sincronizar operação ${op.id}: ${e.message}")
            SingleOperationResult.RETRY
        }
    }
}
