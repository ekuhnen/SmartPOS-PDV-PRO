package com.plugpdv.pdv.utils

import android.content.Context

/**
 * TenantBindingStore
 * 
 * Gerencia a vinculação do terminal ao identificador canônico de tenant (empresa proprietária).
 * Permite que dados em cache (produtos, taxas, mesas) sobrevivam a logins repetidos do mesmo tenant,
 * enquanto protege contra vazamento de dados caso ocorra troca de tenant.
 */
object TenantBindingStore {
    private const val PREFS_NAME = "tenant_binding_prefs"
    private const val KEY_ACTIVE_TENANT_ID = "active_tenant_id"
    private const val KEY_LAST_BOUND_AT = "last_bound_at"

    fun getActiveTenantId(context: Context): String? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ACTIVE_TENANT_ID, null)
    }

    fun setActiveTenantId(context: Context, tenantId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_ACTIVE_TENANT_ID, tenantId)
            .putLong(KEY_LAST_BOUND_AT, System.currentTimeMillis())
            .commit()
    }

    fun clearTenant(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }
}
