package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicChannelConfig
import com.kerobit.kquic.KQuicTransportEvent
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
            core.command(QuicConnectionCore.Command.Receive(datagram, nowMs = 150)).single()
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
            core.command(QuicConnectionCore.Command.Deadline(250))
        )
        assertEquals(
            listOf(
                QuicConnectionCore.Effect.IdleTimeout,
                QuicConnectionCore.Effect.CheckNetwork
            ),
            core.command(QuicConnectionCore.Command.Deadline(1_000))
        )
        assertEquals(
            listOf(QuicConnectionCore.Effect.CloseRequested("application_close")),
            core.close("application_close")
        )
        assertTrue(core.command(QuicConnectionCore.Command.Deadline(2_000)).isEmpty())
        assertTrue(core.close("again").isEmpty())
    }

    @Test
    fun application_commands_and_transport_events_cross_the_typed_boundary() {
        val core = QuicConnectionCore(0, 1_000, 250)
        core.start()
        val config = KQuicChannelConfig("game")

        assertEquals(
            QuicConnectionCore.Effect.OpenChannelRequested(config),
            core.command(QuicConnectionCore.Command.OpenChannel(config)).single()
        )
        assertIs<QuicConnectionCore.Effect.SendRequested>(
            core.command(
                QuicConnectionCore.Command.Send("game", byteArrayOf(1), false, 33)
            ).single()
        )
        val event = KQuicTransportEvent.ChannelClosed("game")
        assertEquals(event, core.event(event).event)
    }
}
