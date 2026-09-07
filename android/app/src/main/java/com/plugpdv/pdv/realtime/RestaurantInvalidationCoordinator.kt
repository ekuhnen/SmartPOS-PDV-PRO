package com.plugpdv.pdv.realtime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The token is deliberately excluded from diagnostic output. */
data class RestaurantSession(val ownerId: String, val userId: String, val token: String) {
    override fun toString() = "RestaurantSession(redacted)"
}

data class RestaurantReadTarget(
    val ownerId: String,
    val userId: String,
    val refresh: suspend (RestaurantSession) -> Unit
)

sealed interface RestaurantSignal {
    data object Connected : RestaurantSignal
    data class Changed(val event: RestaurantEvent) : RestaurantSignal
}

fun interface RestaurantTransport {
    fun subscribe(session: RestaurantSession): Flow<RestaurantSignal>
}

/**
 * Invalidation only: no DTO, payment action or business state enters this coordinator.
 * Refresh every active restaurant read projection conservatively, including Mesa summaries
 * for item/payment changes. There is no inferred one-table/one-comanda relationship.
 */
class RestaurantInvalidationCoordinator(
    private val transport: RestaurantTransport,
    private val debounceMs: Long = 250,
    private val safetyIntervalMs: Long = 60_000,
    private val refreshTables: suspend (RestaurantSession) -> Unit = {}
) {
    suspend fun run(
        sessions: Flow<RestaurantSession?>,
        online: Flow<Boolean>,
        targets: Flow<List<RestaurantReadTarget>>
    ) {
        combine(sessions, online, targets) { session, connected, readers ->
            if (session == null || !connected) null else {
                val matching = readers.filter { it.ownerId == session.ownerId && it.userId == session.userId }
                if (matching.isEmpty()) null else session to matching
            }
        }.collectLatest { active ->
            if (active == null) return@collectLatest
            val (session, readers) = active
            coroutineScope {
                val pending = Channel<Unit>(Channel.CONFLATED)
                // Also covers foreground, login, token rotation and network return.
                pending.trySend(Unit)
                launch {
                    for (ignored in pending) {
                        // Bounded window: a continuous stream cannot starve the UI.
                        delay(debounceMs)
                        while (pending.tryReceive().isSuccess) { /* merge burst */ }
                        try {
                            refreshTables(session)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // A failed Mesa read must not prevent an independent detail read.
                        }
                        for (reader in readers) {
                            try {
                                reader.refresh(session)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (_: Exception) {
                                // Keep the last accepted cache. Other active readers still refresh.
                            }
                        }
                        // Events received during a request retain one trailing refresh.
                    }
                }
                launch {
                    while (isActive) {
                        delay(safetyIntervalMs)
                        pending.trySend(Unit)
                    }
                }
                launch {
                    var retryDelay = 1_000L
                    while (isActive) {
                        try {
                            transport.subscribe(session).collect { signal ->
                                when (signal) {
                                    RestaurantSignal.Connected -> {
                                        retryDelay = 1_000L
                                        pending.trySend(Unit) // Never depend on replay.
                                    }
                                    is RestaurantSignal.Changed -> {
                                        if (signal.event.ownerUserId == session.ownerId) pending.trySend(Unit)
                                    }
                                }
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) {
                            // Reconnect without logging exceptions that may contain credentials.
                        }
                        delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(30_000)
                    }
                }
            }
        }
    }
}
