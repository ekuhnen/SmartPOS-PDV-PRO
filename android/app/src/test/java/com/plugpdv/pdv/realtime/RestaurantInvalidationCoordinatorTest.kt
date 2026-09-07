package com.plugpdv.pdv.realtime

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class RestaurantInvalidationCoordinatorTest {
    private class Transport : RestaurantTransport {
        val events = MutableSharedFlow<RestaurantSignal>(extraBufferCapacity = 100)
        val owners = mutableListOf<String>()
        var active = 0
        var maximum = 0
        var attempts = 0
        var failFirst = false
        override fun subscribe(session: RestaurantSession): Flow<RestaurantSignal> = flow {
            attempts++
            if (failFirst && attempts == 1) error("Subscription failure")
            active++
            maximum = maxOf(maximum, active)
            owners.add(session.ownerId)
            try {
                emitAll(events)
            } finally {
                active--
            }
        }
    }

    private class Fixture(scope: CoroutineScope, interval: Long = 60_000, failFirst: Boolean = false) {
        val transport = Transport().apply { this.failFirst = failFirst }
        val session = MutableStateFlow<RestaurantSession?>(RestaurantSession("A", "userA", "jwtA"))
        val online = MutableStateFlow(true)
        var tables = 0
        var detail = 0
        var canonical = "FREE"
        var rendered = "FREE"
        var holdRead: CompletableDeferred<Unit>? = null
        val reader = RestaurantReadTarget("A", "userA") { detail++ }
        val targets = MutableStateFlow(listOf(reader))
        val job = scope.launch {
            RestaurantInvalidationCoordinator(transport, 30, interval) {
                tables++
                holdRead?.await()
                rendered = canonical
            }.run(session, online, targets)
        }
        suspend fun ready() = until { transport.active == 1 && tables == 1 && detail == 1 }
        suspend fun stop() = job.cancelAndJoin()
        suspend fun event(type: String = "TABLE_CHANGED", seq: Long = 105, owner: String = "A") {
            transport.events.emit(RestaurantSignal.Changed(RestaurantEvent(
                "same-event", seq, owner, type, "mesa5", "evandro", 3, null
            )))
        }
    }

    @Test fun tableEventRefreshesCanonical() = eventRefresh("TABLE_CHANGED")
    @Test fun comandaEventRefreshesCanonical() = eventRefresh("COMANDA_CHANGED")
    @Test fun itemsEventRefreshesCanonical() = eventRefresh("COMANDA_ITEMS_CHANGED")
    @Test fun paymentEventHasOnlyReadCallbacks() = eventRefresh("PAYMENT_CHANGED")

    private fun eventRefresh(type: String) = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            f.canonical = "OCCUPIED"
            f.event(type)
            until { f.tables == 2 && f.detail == 2 }
            assertEquals("OCCUPIED", f.rendered)
        } finally { f.stop() }
    }

    @Test fun duplicatesAndBurstCoalesce() = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            repeat(20) { f.event() }
            until { f.tables == 2 }
            delay(80)
            assertEquals(2, f.tables)
            assertEquals(2, f.detail)
        } finally { f.stop() }
    }

    @Test fun delayedDuplicateAndOutOfOrderCannotRegressState() = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            f.canonical = "OCCUPIED"
            f.event(seq = 105)
            until { f.tables == 2 }
            f.canonical = "FREE"
            f.event(seq = 104)
            until { f.tables == 3 }
            f.event(seq = 104)
            until { f.tables == 4 }
            assertEquals("FREE", f.rendered)
        } finally { f.stop() }
    }

    @Test fun reconnectAlwaysRefreshesWithoutEventReplay() = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            f.transport.events.emit(RestaurantSignal.Connected)
            until { f.tables == 2 }
            f.transport.events.emit(RestaurantSignal.Connected)
            until { f.tables == 3 }
        } finally { f.stop() }
    }

    @Test fun foregroundRefreshAndRotationKeepOneSubscription() = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            f.targets.value = emptyList()
            until { f.transport.active == 0 }
            f.targets.value = listOf(f.reader)
            until { f.tables == 2 }
            assertEquals(1, f.transport.maximum)
        } finally { f.stop() }
    }

    @Test fun logoutUnsubscribesAndIgnoresLateEvents() = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            f.session.value = null
            until { f.transport.active == 0 }
            f.event()
            delay(80)
            assertEquals(1, f.tables)
        } finally { f.stop() }
    }

    @Test fun tenantSwitchDoesNotReuseOldReaderOrSubscription() = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            f.session.value = RestaurantSession("B", "userB", "jwtB")
            until { f.transport.active == 0 }
            assertEquals(1, f.tables)
            var bReads = 0
            f.targets.value = listOf(RestaurantReadTarget("B", "userB") { bReads++ })
            until { bReads == 1 }
            f.event(owner = "A")
            delay(80)
            assertEquals(1, bReads)
            assertEquals(1, f.detail)
            assertEquals(listOf("A", "B"), f.transport.owners)
            assertEquals(1, f.transport.maximum)
        } finally { f.stop() }
    }

    @Test fun tokenRotationRejoinsAndRefreshes() = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            f.session.value = f.session.value!!.copy(token = "rotated")
            until { f.tables == 2 }
            assertEquals(2, f.transport.attempts)
            assertEquals(1, f.transport.maximum)
            assertFalse(f.session.value.toString().contains("rotated"))
        } finally { f.stop() }
    }

    @Test fun offlinePreservesProjectionAndNetworkReturnConverges() = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            f.online.value = false
            until { f.transport.active == 0 }
            f.canonical = "OCCUPIED"
            f.event()
            delay(80)
            assertEquals("FREE", f.rendered)
            f.online.value = true
            until { f.tables == 2 }
            assertEquals("OCCUPIED", f.rendered)
        } finally { f.stop() }
    }

    @Test fun periodicSafetyWorksWithoutRealtimeEvents() = runBlocking {
        val f = Fixture(this, interval = 150)
        try {
            f.ready()
            until { f.tables >= 2 }
            f.targets.value = emptyList()
            until { f.transport.active == 0 }
            val before = f.tables
            delay(220)
            assertEquals(before, f.tables)
        } finally { f.stop() }
    }

    @Test fun eventsDuringSlowReadRetainOneTrailingRefresh() = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            val gate = CompletableDeferred<Unit>()
            f.holdRead = gate
            f.event()
            until { f.tables == 2 }
            repeat(15) { f.event() }
            delay(60)
            assertEquals(2, f.tables)
            gate.complete(Unit)
            until { f.tables == 3 }
            delay(80)
            assertEquals(3, f.tables)
        } finally { f.stop() }
    }

    @Test fun logoutCancelsInFlightRead() = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            f.holdRead = CompletableDeferred()
            f.canonical = "OCCUPIED"
            f.event()
            until { f.tables == 2 }
            f.session.value = null
            until { f.transport.active == 0 }
            f.holdRead!!.complete(Unit)
            delay(80)
            assertEquals("FREE", f.rendered)
        } finally { f.stop() }
    }

    @Test fun multipleComandasUseOnlyCanonicalOccupancy() = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            f.canonical = "OCCUPIED" // Evandro closes, Jana remains open.
            f.event("COMANDA_CHANGED")
            until { f.tables == 2 }
            assertEquals("OCCUPIED", f.rendered)
            f.canonical = "FREE" // Jana closes, canonical aggregate is now free.
            f.event("COMANDA_CHANGED")
            until { f.tables == 3 }
            assertEquals("FREE", f.rendered)
        } finally { f.stop() }
    }

    @Test fun subscriptionFailureRetriesWhileSafetyReadStillWorks() = runBlocking {
        val f = Fixture(this, failFirst = true)
        try {
            until { f.tables == 1 }
            until { f.transport.active == 1 }
            f.transport.events.emit(RestaurantSignal.Connected)
            until { f.tables == 2 }
        } finally { f.stop() }
    }

    @Test fun multipleVisibleReadersShareOneTableReadAndSubscription() = runBlocking {
        val f = Fixture(this)
        try {
            f.ready()
            var secondReads = 0
            f.targets.value = listOf(f.reader, RestaurantReadTarget("A", "userA") { secondReads++ })
            until { secondReads == 1 }
            f.event()
            until { secondReads == 2 }
            assertEquals(3, f.tables)
            assertEquals(1, f.transport.maximum)
        } finally { f.stop() }
    }

    companion object {
        private suspend fun until(check: () -> Boolean) = withTimeout(4_000) {
            while (!check()) delay(2)
        }
    }
}
