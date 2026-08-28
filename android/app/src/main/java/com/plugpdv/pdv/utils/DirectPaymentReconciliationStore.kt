package com.plugpdv.pdv.utils

import android.content.Context

object DirectPaymentReconciliationStore {
    private const val PREFS_NAME = "direct_payment_reconciliation_prefs"
    private const val KEY_REQUIRED = "direct_payment_reconciliation_required"
    private const val KEY_REASON = "reason"
    private const val KEY_PAYMENT_ID = "payment_id"
    private const val KEY_METHOD = "method"
    private const val KEY_TIMESTAMP = "timestamp"

    data class ReconciliationMarker(
        val isRequired: Boolean,
        val reason: String?,
        val paymentId: String?,
        val method: String?,
        val timestamp: Long
    )

    fun setMarker(context: Context, reason: String, paymentId: String?, method: String?) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean(KEY_REQUIRED, true)
            .putString(KEY_REASON, reason)
            .putString(KEY_PAYMENT_ID, paymentId)
            .putString(KEY_METHOD, method)
            .putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            .commit()
    }

    fun getMarker(context: Context): ReconciliationMarker {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val isRequired = prefs.getBoolean(KEY_REQUIRED, false)
        val reason = prefs.getString(KEY_REASON, null)
        val paymentId = prefs.getString(KEY_PAYMENT_ID, null)
        val method = prefs.getString(KEY_METHOD, null)
        val timestamp = prefs.getLong(KEY_TIMESTAMP, 0L)
        return ReconciliationMarker(isRequired, reason, paymentId, method, timestamp)
    }

    fun isReconciliationRequired(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_REQUIRED, false)
    }

    fun clearMarker(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }
}
