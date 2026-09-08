package com.plugpdv.pdv.ui.sale

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException

/** Presentation only: a committed payment is never retried by receipt delivery. */
internal object CheckoutReceiptSequence {
    suspend fun deliver(
        state: CheckoutUiState,
        transaction: () -> Unit,
        closing: suspend () -> Unit,
        printError: () -> Unit,
        finish: () -> Unit
    ) {
        if (!state.paymentSuccess || state.isPendingSync || state.isAwaitingProvider || state.isLoading) return
        try { transaction() } catch (e: Exception) { printError() }
        if (state.balanceBaseMinor == 0L) {
            try { closing() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { printError() }
        }
        // In particular, never open the fiscal dialog while the receipt fetch is pending.
        finish()
    }

    /** Claim before network/printer work. Failed/unknown dispatch is manually reprinted,
     * never retried by a repeated success emission or a resumed fragment. */
    @Synchronized
    fun claimFinal(prefs: SharedPreferences, comandaId: String): Boolean {
        require(comandaId.isNotBlank())
        val attempted = "CLOSING_RECEIPT_ATTEMPTED_$comandaId"
        if (prefs.getBoolean(attempted, false) ||
            prefs.getBoolean("CLOSING_RECEIPT_PRINTED_$comandaId", false)) return false
        check(prefs.edit().putBoolean(attempted, true).commit())
        return true
    }

    suspend fun printFinal(prefs: SharedPreferences, comandaId: String, reprint: Boolean,
        print: suspend () -> Boolean) {
        if (!reprint && !claimFinal(prefs, comandaId)) return
        check(print())
        if (!reprint) prefs.edit().putBoolean("CLOSING_RECEIPT_PRINTED_$comandaId", true).apply()
    }
}
