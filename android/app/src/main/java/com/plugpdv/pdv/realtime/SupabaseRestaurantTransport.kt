package com.plugpdv.pdv.realtime

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.Realtime
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SupabaseRestaurantTransport @Inject constructor(
    private val supabase: SupabaseClient
) : RestaurantTransport {
    @OptIn(SupabaseInternal::class)
    override fun subscribe(session: RestaurantSession) = channelFlow {
        val realtime = supabase.realtime
        // Existing terminal JWT, also used on automatic SDK rejoin. No GoTrue user is fabricated.
        realtime.config.jwtToken = session.token
        // postgres_changes topics are client labels, not server Broadcast authorization topics.
        val channel = supabase.channel("restaurant-invalidation-${session.ownerId}")
        val changes = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "restaurant_realtime_events"
            filter("owner_user_id", FilterOperator.EQ, session.ownerId)
        }
        try {
            launch {
                var connected = false
                realtime.status.collectLatest { status ->
                    when (status) {
                        Realtime.Status.CONNECTED -> connected = true
                        Realtime.Status.DISCONNECTED -> {
                            if (connected) error("Restaurant transport disconnected")
                        }
                        Realtime.Status.CONNECTING -> {
                            delay(20_000)
                            error("Restaurant transport connection timed out")
                        }
                        else -> Unit
                    }
                }
            }
            launch {
                changes.collect { change ->
                    RestaurantEvent.decode(change.record)?.let { event ->
                        if (event.ownerUserId == session.ownerId) send(RestaurantSignal.Changed(event))
                    }
                }
            }
            launch {
                var joined = false
                channel.status.collectLatest { status ->
                    when (status) {
                        RealtimeChannel.Status.SUBSCRIBED -> {
                            joined = true
                            send(RestaurantSignal.Connected)
                        }
                        RealtimeChannel.Status.UNSUBSCRIBED -> {
                            if (joined) error("Restaurant subscription interrupted")
                        }
                        RealtimeChannel.Status.SUBSCRIBING -> {
                            delay(20_000)
                            error("Restaurant rejoin timed out")
                        }
                        else -> Unit
                    }
                }
            }
            try {
                withTimeout(20_000) { channel.subscribe(blockUntilSubscribed = true) }
            } catch (_: TimeoutCancellationException) {
                error("Restaurant subscription timed out")
            }
            awaitCancellation()
        } finally {
            withContext(NonCancellable) {
                try {
                    withTimeoutOrNull(2_000) {
                        // Also leave a pending join; removeChannel only leaves SUBSCRIBED in 2.4.3.
                        channel.unsubscribe()
                        realtime.removeChannel(channel)
                    }
                } catch (_: Exception) {
                    // Offline teardown must still remove the SDK's automatic-rejoin entry.
                } finally {
                    realtime.run { channel.deleteChannel(channel) }
                    if (realtime.config.jwtToken == session.token) realtime.config.jwtToken = null
                }
            }
        }
    }
}
