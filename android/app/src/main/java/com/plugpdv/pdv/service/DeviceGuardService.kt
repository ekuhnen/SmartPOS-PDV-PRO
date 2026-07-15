package com.plugpdv.pdv.service

import android.content.Context
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import com.plugpdv.pdv.utils.KillSwitchManager
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceGuardService @Inject constructor(
    private val supabase: SupabaseClient
) {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var channel: RealtimeChannel? = null

    companion object {
        private const val TAG = "DeviceGuardService"
    }

    fun start(context: Context, userId: String, deviceId: String) {
        scope.launch {
            try {
                channel = supabase.channel("device-guard-$deviceId")

                // 1) Monitorar bloqueio do DISPOSITIVO
                channel!!.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "pdv_devices"
                    filter("id", FilterOperator.EQ, deviceId)
                }.onEach { change ->
                    val blocked = change.record["blocked"]
                        ?.toString()?.lowercase()?.toBooleanStrictOrNull() ?: false
                    if (blocked) {
                        val reason = change.record["blocked_reason"]?.toString()?.trim('"')
                            ?: "Dispositivo bloqueado pelo administrador"
                        Log.w(TAG, "Device blocked by admin: $reason")
                        KillSwitchManager.forceLogout(context, reason)
                    }
                }.launchIn(scope)

                // 2) Monitorar revogação do USUÁRIO
                channel!!.postgresChangeFlow<PostgresAction.Update>(schema = "public") {
                    table = "company_users"
                    filter("auth_user_id", FilterOperator.EQ, userId)
                }.onEach { change ->
                    val active = change.record["active"]
                        ?.toString()?.lowercase()?.toBooleanStrictOrNull() ?: true
                    if (!active) {
                        val reason = change.record["blocked_reason"]?.toString()?.trim('"')
                            ?: "Acesso revogado pelo administrador"
                        Log.w(TAG, "User blocked by admin: $reason")
                        KillSwitchManager.forceLogout(context, reason)
                    }
                }.launchIn(scope)

                channel!!.subscribe()
                Log.i(TAG, "DeviceGuardService started for device=$deviceId, user=$userId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start DeviceGuardService", e)
            }
        }
    }

    fun stop() {
        scope.launch {
            try {
                channel?.unsubscribe()
                channel = null
                Log.i(TAG, "DeviceGuardService stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping DeviceGuardService", e)
            }
        }
    }
}
