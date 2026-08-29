package com.plugpdv.pdv.utils

import android.content.Context
import com.plugpdv.pdv.database.ComandaSnapshotEntity
import java.math.BigDecimal

enum class SnapshotAuthorityDecision {
    USABLE,
    RECONCILIATION_REQUIRED,
    MISSING_AUTHORITY,
    WRONG_TENANT,
    WRONG_COMANDA,
    CONFLICT,
    CLOSED,
    CANCELLED
}

object ComandaSnapshotAuthorityPolicy {

    /**
     * Converts a minor-unit financial amount to BigDecimal using the frozen accounting scale of the snapshot.
     */
    fun fromMinorUnitsWithFrozenScale(minor: Long, digits: Int): BigDecimal {
        return BigDecimal.valueOf(minor).movePointLeft(digits)
    }

    /**
     * Evaluates whether a ComandaSnapshot is usable as operational authority.
     */
    fun evaluate(
        snapshot: ComandaSnapshotEntity?,
        expectedComandaId: String?,
        context: Context
    ): SnapshotAuthorityDecision {
        if (snapshot == null) return SnapshotAuthorityDecision.MISSING_AUTHORITY

        val activeTenantId = TenantBindingStore.getActiveTenantId(context)
        if (!activeTenantId.isNullOrBlank() && snapshot.tenantId != activeTenantId) {
            return SnapshotAuthorityDecision.WRONG_TENANT
        }

        if (!expectedComandaId.isNullOrBlank() && snapshot.serverComandaId != expectedComandaId) {
            return SnapshotAuthorityDecision.WRONG_COMANDA
        }

        if (snapshot.syncStatus == "CONFLICT") {
            return SnapshotAuthorityDecision.CONFLICT
        }

        if (snapshot.requiresReconciliation) {
            return SnapshotAuthorityDecision.RECONCILIATION_REQUIRED
        }

        if (snapshot.baseCurrency.isNullOrBlank()) {
            return SnapshotAuthorityDecision.MISSING_AUTHORITY
        }

        val digits = snapshot.baseMinorUnitDigits
        if (digits == null || digits !in 0..4) {
            return SnapshotAuthorityDecision.MISSING_AUTHORITY
        }

        if (snapshot.totalBaseMinor == null || snapshot.paidBaseMinor == null || snapshot.balanceBaseMinor == null) {
            return SnapshotAuthorityDecision.MISSING_AUTHORITY
        }

        if (snapshot.localStatus.equals("CLOSED", ignoreCase = true) || snapshot.serverStatus.equals("FECHADA", ignoreCase = true)) {
            return SnapshotAuthorityDecision.CLOSED
        }

        if (snapshot.localStatus.equals("CANCELLED", ignoreCase = true) || snapshot.serverStatus.equals("CANCELADA", ignoreCase = true)) {
            return SnapshotAuthorityDecision.CANCELLED
        }

        return SnapshotAuthorityDecision.USABLE
    }
}
