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

        val normalizedLocalStatus = when (detail.status.uppercase()) {
            "ABERTA", "EM_CONSUMO", "AGUARDANDO_PAGAMENTO" -> "OPEN"
            "FECHADA" -> "CLOSED"
            "CANCELADA" -> "CANCELLED"
            else -> "UNKNOWN"
        }

        val syncStatus = if (existing != null && (existing.syncStatus == "PENDING_MUTATIONS" || existing.syncStatus == "CONFLICT")) {
            existing.syncStatus
        } else {
            "SYNCED"
        }

        val serverRevision: Long? = null
        val localRevision: Long = existing?.localRevision ?: 0L
        val serverUpdatedAt: Long? = null
        val cachedAt = System.currentTimeMillis()

        var requiresReconciliation = detail.requiresReconciliation
        var reconciliationReason: String? = if (detail.requiresReconciliation) "SERVER_RECONCILIATION_REQUIRED" else null

        val frozenBaseCurrency: String?
        val frozenBaseMinorUnitDigits: Int?
        val totalBaseMinor: Long?
        val paidBaseMinor: Long?
        val balanceBaseMinor: Long?

        val remoteBaseCurrency = detail.baseCurrency?.trim()?.takeIf { it.isNotEmpty() }?.uppercase()

        if (existing?.baseCurrency != null) {
            // Case B / C / D for existing frozen authority
            frozenBaseCurrency = existing.baseCurrency
            frozenBaseMinorUnitDigits = existing.baseMinorUnitDigits

            if (remoteBaseCurrency == null) {
                // Case D: existing valid snapshot, remote currency missing
                requiresReconciliation = true
                reconciliationReason = "BASE_CURRENCY_MISSING"
                totalBaseMinor = existing.totalBaseMinor
                paidBaseMinor = existing.paidBaseMinor
                balanceBaseMinor = existing.balanceBaseMinor
            } else if (!remoteBaseCurrency.equals(existing.baseCurrency, ignoreCase = true)) {
                // Case C: Currency conflict! Do NOT overwrite frozen authority or amounts
                requiresReconciliation = true
                reconciliationReason = "BASE_CURRENCY_CHANGED"
                totalBaseMinor = existing.totalBaseMinor
                paidBaseMinor = existing.paidBaseMinor
                balanceBaseMinor = existing.balanceBaseMinor
            } else {
                // Case B: Currency matches frozen authority
                val (tot, paid, bal, reqRec, recReason) = computeAuthoritativeMinorTotals(detail, frozenBaseCurrency)
                totalBaseMinor = tot
                paidBaseMinor = paid
                balanceBaseMinor = bal
                if (reqRec) {
                    requiresReconciliation = true
                    if (reconciliationReason == null) reconciliationReason = recReason
                }
            }
        } else {
            // New snapshot or existing with null base currency
            if (remoteBaseCurrency == null) {
                // Case D: new snapshot with missing base currency
                frozenBaseCurrency = null
                frozenBaseMinorUnitDigits = null
                totalBaseMinor = null
                paidBaseMinor = null
                balanceBaseMinor = null
                requiresReconciliation = true
                reconciliationReason = "BASE_CURRENCY_MISSING"
            } else {
                // Case A: Freeze new base currency
                frozenBaseCurrency = remoteBaseCurrency
                frozenBaseMinorUnitDigits = MoneyDecimal.getDecimals(remoteBaseCurrency)

                val (tot, paid, bal, reqRec, recReason) = computeAuthoritativeMinorTotals(detail, frozenBaseCurrency)
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

        val entity = ComandaSnapshotEntity(
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
            syncStatus = syncStatus,
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

    private fun computeAuthoritativeMinorTotals(
        detail: ComandaDetailResponse,
        baseCurrency: String
    ): MoneyComputationResult {
        var reqRec = false
        var reason: String? = null

        val totalBaseMinor = try {
            MoneyDecimal.toMinorUnits(BigDecimal.valueOf(detail.total), baseCurrency)
        } catch (e: Exception) {
            null
        }

        // paidBaseMinor: ONLY detail.totalPagoBase (NO fallback to detail.totalPago)
        val paidBaseMinor = detail.totalPagoBase?.let {
            try {
                MoneyDecimal.toMinorUnits(BigDecimal.valueOf(it), baseCurrency)
            } catch (e: Exception) {
                null
            }
        }

        // balanceBaseMinor: prefer authoritative detail.saldoBase
        val balanceBaseMinor = detail.saldoBase?.let {
            try {
                MoneyDecimal.toMinorUnits(BigDecimal.valueOf(it), baseCurrency)
            } catch (e: Exception) {
                null
            }
        }

        if (paidBaseMinor == null || balanceBaseMinor == null) {
            reqRec = true
            reason = "BASE_MONEY_SUMMARY_MISSING"
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

    suspend fun getAllForTenant(tenantId: String): List<ComandaSnapshotEntity> {
        return comandaSnapshotDao.getAllForTenant(tenantId)
    }
}
