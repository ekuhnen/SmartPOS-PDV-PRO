package com.plugpdv.pdv.repository

sealed class ReconciliationResult {
    /**
     * Resolution applied successfully.
     * @param resultState The new state of the original mutation (e.g., "COMPLETED", "CANCELLED", "PENDING").
     */
    data class Success(val resultState: String) : ReconciliationResult()

    /**
     * Resolution was rejected — authority mismatch, wrong state, or missing identity.
     * The mutation was NOT modified.
     */
    data class Rejected(val reason: String) : ReconciliationResult()

    /**
     * The mutation was already in a finalized state (COMPLETED, CANCELLED, SYNCED).
     * Idempotent call — no action taken, no error.
     */
    object AlreadyResolved : ReconciliationResult()
}
