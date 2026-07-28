package com.kerobit.kquic.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuicConnectionCoreTest {
    @Test
    fun explicit_inputs_drive_lifecycle_datagrams_and_wire_stats() {
        val core = QuicConnectionCore(
            startedAtMs = 100,
            idleTimeoutMs = 1_000,
            networkCheckIntervalMs = 250
        )
        assertNull(core.nextDeadlineMs())
        assertTrue(core.start())
        assertFalse(core.start())

        val datagram = KQuicInboundDatagram(
            KQuicSocketAddress("127.0.0.1", 4_432),
            ByteArray(24)
        )
        assertIs<QuicConnectionCore.Effect.DatagramReady>(
            core.receive(datagram, nowMs = 150).single()
        )
        val outgoing = core.transmit(
            destination = KQuicSocketAddress("127.0.0.1", 4_433),
            payload = ByteArray(12),
            nowMs = 175
        )
        assertIs<QuicConnectionCore.Effect.DatagramToSend>(outgoing)

        assertEquals(
            QuicConnectionCore.WireStats(12, 24, 1, 1),
            core.stats()
        )
        assertEquals(350, core.nextDeadlineMs())
    }

    @Test
    fun deadlines_and_close_produce_typed_effects() {
        val core = QuicConnectionCore(
            startedAtMs = 0,
            idleTimeoutMs = 1_000,
            networkCheckIntervalMs = 250
        )
        core.start()

        assertEquals(
            listOf(QuicConnectionCore.Effect.CheckNetwork),
            core.deadline(250)
        )
        assertEquals(
            listOf(
                QuicConnectionCore.Effect.IdleTimeout,
                QuicConnectionCore.Effect.CheckNetwork
            ),
            core.deadline(1_000)
        )
        assertEquals(
            listOf(QuicConnectionCore.Effect.CloseRequested("application_close")),
            core.close("application_close")
        )
        assertTrue(core.deadline(2_000).isEmpty())
        assertTrue(core.close("again").isEmpty())
    }
}
