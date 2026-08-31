package com.plugpdv.pdv.utils

import android.content.Context

sealed class CashierAuthorityState {
    data class OPEN(
        val sessionId: String,
        val tenantId: String,
        val userId: String?,
        val updatedAt: Long
    ) : CashierAuthorityState()

    data class CLOSED(
        val tenantId: String,
        val userId: String?,
        val updatedAt: Long
    ) : CashierAuthorityState()

    data class UNKNOWN(
        val reason: String? = null
    ) : CashierAuthorityState()
}

/**
 * CashierAuthorityStore
 *
 * Persists and validates the last-known operational authority of the cashier,
 * strictly bound to the active tenant and authenticated user.
 *
 * Non-negotiable invariant:
 * Lack of internet must NEVER transform a known OPEN cashier into CLOSED.
 * Offline network failures preserve the last-known authority state.
 */
object CashierAuthorityStore {
    private const val PREFS_NAME = "cashier_authority_prefs"
    private const val KEY_TENANT_ID = "ca_tenant_id"
    private const val KEY_USER_ID = "ca_user_id"
    private const val KEY_STATE = "ca_state" // "OPEN", "CLOSED", "UNKNOWN"
    private const val KEY_SESSION_ID = "ca_session_id"
    private const val KEY_UPDATED_AT = "ca_updated_at"

    fun getAuthority(context: Context, activeTenantId: String?, activeUserId: String?): CashierAuthorityState {
        if (activeTenantId.isNullOrBlank()) {
            return CashierAuthorityState.UNKNOWN("MISSING_ACTIVE_TENANT")
        }
        if (activeUserId.isNullOrBlank()) {
            return CashierAuthorityState.UNKNOWN("MISSING_ACTIVE_USER")
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedTenant = prefs.getString(KEY_TENANT_ID, null)
        val storedUser = prefs.getString(KEY_USER_ID, null)
        val storedState = prefs.getString(KEY_STATE, null)
        val storedSessionId = prefs.getString(KEY_SESSION_ID, null)
        val storedUpdatedAt = prefs.getLong(KEY_UPDATED_AT, 0L)

        if (storedTenant != activeTenantId) {
            return CashierAuthorityState.UNKNOWN("TENANT_MISMATCH")
        }

        if (storedUser != activeUserId) {
            return CashierAuthorityState.UNKNOWN("USER_MISMATCH")
        }

        return when (storedState) {
            "OPEN" -> {
                if (!storedSessionId.isNullOrBlank()) {
                    CashierAuthorityState.OPEN(
                        sessionId = storedSessionId,
                        tenantId = activeTenantId,
                        userId = activeUserId,
                        updatedAt = storedUpdatedAt
                    )
                } else {
                    CashierAuthorityState.UNKNOWN("MISSING_SESSION_ID_FOR_OPEN")
                }
            }
            "CLOSED" -> {
                CashierAuthorityState.CLOSED(
                    tenantId = activeTenantId,
                    userId = activeUserId,
                    updatedAt = storedUpdatedAt
                )
            }
            else -> CashierAuthorityState.UNKNOWN("NO_STORED_AUTHORITY")
        }
    }

    fun setOpen(context: Context, tenantId: String, userId: String?, sessionId: String) {
        if (tenantId.isBlank() || userId.isNullOrBlank() || sessionId.isBlank()) {
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_TENANT_ID, tenantId)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_STATE, "OPEN")
            .putString(KEY_SESSION_ID, sessionId)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()

        // Sync with legacy main prefs for backward compatibility
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(Constants.SESSION_ID, sessionId)
            .apply()
    }

    fun setClosed(context: Context, tenantId: String, userId: String?) {
        if (tenantId.isBlank() || userId.isNullOrBlank()) {
            return
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_TENANT_ID, tenantId)
            .putString(KEY_USER_ID, userId)
            .putString(KEY_STATE, "CLOSED")
            .remove(KEY_SESSION_ID)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()

        // Remove legacy session
        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(Constants.SESSION_ID)
            .apply()
    }

    fun clearAuthority(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().apply()

        context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(Constants.SESSION_ID)
            .apply()
    }
}
