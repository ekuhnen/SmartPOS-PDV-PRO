package com.plugpdv.pdv.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.plugpdv.pdv.database.ComandaSnapshotDao
import com.plugpdv.pdv.database.ComandaSnapshotEntity
import com.plugpdv.pdv.models.ComandaDetailResponse
import com.plugpdv.pdv.models.Table
import com.plugpdv.pdv.utils.MoneyDecimal
import com.plugpdv.pdv.utils.TenantBindingStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ComandaSnapshotRepository(
    private val context: Context,
    private val comandaSnapshotDao: ComandaSnapshotDao,
    private val gson: Gson
) {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        comandaSnapshotDao: ComandaSnapshotDao
    ) : this(context, comandaSnapshotDao, Gson())

    companion object {
        private const val TAG = "ComandaSnapshotRepo"

        fun toMinorUnitsWithFrozenScale(amount: BigDecimal, digits: Int): Long {
            val scaled = amount.setScale(digits, RoundingMode.HALF_UP)
            return scaled.movePointRight(digits).longValueExact()
        }
    }

    suspend fun cacheRemoteDetail(
        detail: ComandaDetailResponse,
        table: Table? = null
    ): ComandaSnapshotEntity? {
        val tenantId = TenantBindingStore.getActiveTenantId(context)
        if (tenantId.isNullOrBlank()) {
            Log.w(TAG, "TENANT_BINDING_MISSING: cannot persist comanda snapshot without active tenant")
            return null
        }

        val existing = comandaSnapshotDao.getByServerComandaId(tenantId, detail.id)
        val localComandaId = existing?.localComandaId ?: UUID.randomUUID().toString()

        val tableId = detail.mesaId ?: table?.id ?: existing?.tableId
        val tableNumber = detail.numero ?: table?.number ?: existing?.tableNumber
        val customerIdentifier = detail.nomeCliente ?: table?.customerName ?: existing?.customerIdentifier

        val hasLocalPendingState = existing?.syncStatus in listOf("PENDING_MUTATIONS", "CONFLICT")

        val serverRevision: Long? = null
        val localRevision: Long = existing?.localRevision ?: 0L
        val serverUpdatedAt: Long? = null
        val cachedAt = System.currentTimeMillis()

        val remoteBaseCurrency = detail.baseCurrency?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()

        val entity: ComandaSnapshotEntity

        if (hasLocalPendingState && existing != null) {
            // PENDING LOCAL STATE MERGE: Remote refresh MUST NOT destroy the local operational projection
            var requiresReconciliation = existing.requiresReconciliation || detail.requiresReconciliation
            var reconciliationReason: String? = when {
                existing.reconciliationReason != null -> existing.reconciliationReason
                detail.requiresReconciliation -> "SERVER_RECONCILIATION_REQUIRED"
                else -> null
            }

            if (existing.baseCurrency != null && existing.baseMinorUnitDigits == null) {
                requiresReconciliation = true
                reconciliationReason = "FROZEN_MINOR_UNIT_MISSING"
            } else if (remoteBaseCurrency == null) {
                requiresReconciliation = true
                if (reconciliationReason == null) reconciliationReason = "BASE_CURRENCY_MISSING"
            } else if (existing.baseCurrency != null && !remoteBaseCurrency.equals(existing.baseCurrency, ignoreCase = true)) {
                requiresReconciliation = true
                reconciliationReason = "BASE_CURRENCY_CHANGED"
            } else if (detail.requiresReconciliation) {
                requiresReconciliation = true
                if (reconciliationReason == null) reconciliationReason = "SERVER_RECONCILIATION_REQUIRED"
            }

            entity = ComandaSnapshotEntity(
                localComandaId = existing.localComandaId,
                serverComandaId = detail.id,
                tenantId = tenantId,
                tableId = tableId,
                tableNumber = tableNumber,
                customerIdentifier = customerIdentifier,
                baseCurrency = existing.baseCurrency,
                baseMinorUnitDigits = existing.baseMinorUnitDigits,
                serverStatus = detail.status,
                localStatus = existing.localStatus,
                syncStatus = existing.syncStatus,
                serverRevision = serverRevision,
                localRevision = existing.localRevision,
                totalBaseMinor = existing.totalBaseMinor,
                paidBaseMinor = existing.paidBaseMinor,
                balanceBaseMinor = existing.balanceBaseMinor,
                itemsJson = existing.itemsJson,
                paymentsJson = existing.paymentsJson,
                requiresReconciliation = requiresReconciliation,
                reconciliationReason = reconciliationReason,
                serverUpdatedAt = serverUpdatedAt,
                cachedAt = cachedAt
            )
        } else {
            // Normal SYNCED refresh or initial snapshot: derive reconciliation dynamically from verified invariants
            val normalizedLocalStatus = when (detail.status.uppercase()) {
                "ABERTA", "EM_CONSUMO", "AGUARDANDO_PAGAMENTO" -> "OPEN"
                "FECHADA" -> "CLOSED"
                "CANCELADA" -> "CANCELLED"
                else -> "UNKNOWN"
            }

            var requiresReconciliation = detail.requiresReconciliation
            var reconciliationReason: String? = if (detail.requiresReconciliation) "SERVER_RECONCILIATION_REQUIRED" else null

            val frozenBaseCurrency: String?
            val frozenBaseMinorUnitDigits: Int?
            val totalBaseMinor: Long?
            val paidBaseMinor: Long?
            val balanceBaseMinor: Long?

            if (existing?.reconciliationReason == "BASE_CURRENCY_CHANGED") {
                // For V1, keep BASE_CURRENCY_CHANGED sticky
                frozenBaseCurrency = existing.baseCurrency
                frozenBaseMinorUnitDigits = existing.baseMinorUnitDigits
                totalBaseMinor = existing.totalBaseMinor
                paidBaseMinor = existing.paidBaseMinor
                balanceBaseMinor = existing.balanceBaseMinor
                requiresReconciliation = true
                reconciliationReason = "BASE_CURRENCY_CHANGED"
            } else if (existing?.baseCurrency != null) {
                // Existing snapshot with frozen currency
                frozenBaseCurrency = existing.baseCurrency
                frozenBaseMinorUnitDigits = existing.baseMinorUnitDigits

                if (frozenBaseMinorUnitDigits == null) {
                    requiresReconciliation = true
                    reconciliationReason = "FROZEN_MINOR_UNIT_MISSING"
                    totalBaseMinor = existing.totalBaseMinor
                    paidBaseMinor = existing.paidBaseMinor
                    balanceBaseMinor = existing.balanceBaseMinor
                } else if (remoteBaseCurrency == null) {
                    requiresReconciliation = true
                    if (reconciliationReason == null) reconciliationReason = "BASE_CURRENCY_MISSING"
                    totalBaseMinor = existing.totalBaseMinor
                    paidBaseMinor = existing.paidBaseMinor
                    balanceBaseMinor = existing.balanceBaseMinor
                } else if (!remoteBaseCurrency.equals(existing.baseCurrency, ignoreCase = true)) {
                    requiresReconciliation = true
                    reconciliationReason = "BASE_CURRENCY_CHANGED"
                    totalBaseMinor = existing.totalBaseMinor
                    paidBaseMinor = existing.paidBaseMinor
                    balanceBaseMinor = existing.balanceBaseMinor
                } else {
                    // Currency matches frozen authority -> compute using FROZEN digits
                    val (tot, paid, bal, reqRec, recReason) = computeAuthoritativeMinorTotalsWithScale(detail, frozenBaseMinorUnitDigits)
                    totalBaseMinor = tot
                    paidBaseMinor = paid
                    balanceBaseMinor = bal
                    if (reqRec) {
                        requiresReconciliation = true
                        if (reconciliationReason == null) reconciliationReason = recReason
                    }
                }
            } else {
                // New snapshot or existing snapshot with previously missing baseCurrency (Case 4: recovery)
                if (remoteBaseCurrency == null) {
                    frozenBaseCurrency = null
                    frozenBaseMinorUnitDigits = null
                    totalBaseMinor = null
                    paidBaseMinor = null
                    balanceBaseMinor = null
                    requiresReconciliation = true
                    if (reconciliationReason == null) reconciliationReason = "BASE_CURRENCY_MISSING"
                } else {
                    // Legitimate first establishment of previously missing authority
                    frozenBaseCurrency = remoteBaseCurrency
                    val digits = MoneyDecimal.getDecimals(remoteBaseCurrency)
                    frozenBaseMinorUnitDigits = digits

                    val (tot, paid, bal, reqRec, recReason) = computeAuthoritativeMinorTotalsWithScale(detail, digits)
                    totalBaseMinor = tot
                    paidBaseMinor = paid
                    balanceBaseMinor = bal
                    if (reqRec) {
                        requiresReconciliation = true
                        if (reconciliationReason == null) reconciliationReason = recReason
                    }
                }
            }

            val itemsJson = try {
                gson.toJson(detail.itens)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to serialize items JSON: ${e.message}", e)
                existing?.itemsJson ?: "[]"
            }

            val paymentsJson = try {
                gson.toJson(detail.pagamentos)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to serialize payments JSON: ${e.message}", e)
                existing?.paymentsJson ?: "[]"
            }

            entity = ComandaSnapshotEntity(
                localComandaId = localComandaId,
                serverComandaId = detail.id,
                tenantId = tenantId,
                tableId = tableId,
                tableNumber = tableNumber,
                customerIdentifier = customerIdentifier,
                baseCurrency = frozenBaseCurrency,
                baseMinorUnitDigits = frozenBaseMinorUnitDigits,
                serverStatus = detail.status,
                localStatus = normalizedLocalStatus,
                syncStatus = "SYNCED",
                serverRevision = serverRevision,
                localRevision = localRevision,
                totalBaseMinor = totalBaseMinor,
                paidBaseMinor = paidBaseMinor,
                balanceBaseMinor = balanceBaseMinor,
                itemsJson = itemsJson,
                paymentsJson = paymentsJson,
                requiresReconciliation = requiresReconciliation,
                reconciliationReason = reconciliationReason,
                serverUpdatedAt = serverUpdatedAt,
                cachedAt = cachedAt
            )
        }

        comandaSnapshotDao.upsert(entity)
        return entity
    }

    private data class MoneyComputationResult(
        val totalBaseMinor: Long?,
        val paidBaseMinor: Long?,
        val balanceBaseMinor: Long?,
        val requiresReconciliation: Boolean,
        val reconciliationReason: String?
    )

    private fun computeAuthoritativeMinorTotalsWithScale(
        detail: ComandaDetailResponse,
        digits: Int
    ): MoneyComputationResult {
        var reqRec = false
        var reason: String? = null

        val totalBaseMinor = try {
            toMinorUnitsWithFrozenScale(BigDecimal.valueOf(detail.total), digits)
        } catch (e: Exception) {
            null
        }

        // paidBaseMinor: ONLY detail.totalPagoBase (NO fallback to detail.totalPago)
        val paidBaseMinor = detail.totalPagoBase?.let {
            try {
                toMinorUnitsWithFrozenScale(BigDecimal.valueOf(it), digits)
            } catch (e: Exception) {
                null
            }
        }

        // balanceBaseMinor: prefer authoritative detail.saldoBase
        val balanceBaseMinor = detail.saldoBase?.let {
            try {
                toMinorUnitsWithFrozenScale(BigDecimal.valueOf(it), digits)
            } catch (e: Exception) {
                null
            }
        }

        if (totalBaseMinor == null) {
            reqRec = true
            reason = "BASE_MONEY_SUMMARY_INVALID"
        } else if (paidBaseMinor == null || balanceBaseMinor == null) {
            reqRec = true
            reason = if (detail.totalPagoBase == null || detail.saldoBase == null) {
                "BASE_MONEY_SUMMARY_MISSING"
            } else {
                "BASE_MONEY_SUMMARY_INVALID"
            }
        }

        return MoneyComputationResult(
            totalBaseMinor = totalBaseMinor,
            paidBaseMinor = paidBaseMinor,
            balanceBaseMinor = balanceBaseMinor,
            requiresReconciliation = reqRec,
            reconciliationReason = reason
        )
    }

    suspend fun getByLocalId(localId: String): ComandaSnapshotEntity? {
        return comandaSnapshotDao.getByLocalId(localId)
    }

    suspend fun getByServerComandaId(tenantId: String, serverComandaId: String): ComandaSnapshotEntity? {
        return comandaSnapshotDao.getByServerComandaId(tenantId, serverComandaId)
    }

    suspend fun getByTableId(tenantId: String, tableId: String): ComandaSnapshotEntity? {
        return comandaSnapshotDao.getByTableId(tenantId, tableId)
    }

    suspend fun getSnapshotsByTableId(tenantId: String, tableId: String): List<ComandaSnapshotEntity> {
        return comandaSnapshotDao.getSnapshotsByTableId(tenantId, tableId)
    }

    suspend fun getAllForTenant(tenantId: String): List<ComandaSnapshotEntity> {
        return comandaSnapshotDao.getAllForTenant(tenantId)
    }
}
