package com.plugpdv.pdv.realtime

import android.content.Context
import com.plugpdv.pdv.utils.Constants
import com.plugpdv.pdv.utils.TenantBindingStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Prevent a completed old-session HTTP request from entering the current read projection. */
internal class RestaurantReadSessionGuard(private val context: Context, private val requestToken: String? = null) {
    private val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
    private val token = prefs.getString(Constants.TOKEN, null)
    private val user = prefs.getString(Constants.USER_ID, null)
    private val owner = TenantBindingStore.getActiveTenantId(context)

    suspend fun check() {
        currentCoroutineContext().ensureActive()
        if ((token != null && requestToken != null && token != requestToken) ||
            token != prefs.getString(Constants.TOKEN, null) ||
            user != prefs.getString(Constants.USER_ID, null) ||
            owner != TenantBindingStore.getActiveTenantId(context)) {
            throw CancellationException("Restaurant read session changed")
        }
    }
}
