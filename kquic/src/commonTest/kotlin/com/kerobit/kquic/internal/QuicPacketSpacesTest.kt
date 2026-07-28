package com.kerobit.kquic.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuicPacketSpacesTest {
    @Test
    fun congestion_bypass_frames_are_dequeued_before_regular_frames() {
        val queue = QuicFrameSendQueue()
        val stream = QuicQueuedFrame(
            QuicFrame.Stream(0uL, 0uL, false, byteArrayOf(1)),
            estimatedBytes = 100
        )
        val ack = QuicQueuedFrame(
            QuicFrame.Ack(listOf(QuicAckRange(0uL, 0uL)), 0uL),
            estimatedBytes = 50,
            bypassCongestion = true
        )

        queue.addLast(stream)
        queue.addLast(ack)

        assertEquals(ack, queue.removeFirst())
        assertEquals(stream, queue.removeFirst())
        assertFalse(queue.isNotEmpty)
    }

    @Test
    fun pto_probe_bypasses_a_full_congestion_window_and_retains_priority() {
        val queue = QuicFrameSendQueue()
        val stream = QuicQueuedFrame(QuicFrame.Ping, 65)
        val probe = QuicQueuedFrame(QuicFrame.Ping, 65, bypassCongestion = true)
        val recovery = QuicRecovery()
        repeat(14) { packet ->
            recovery.onPacketSent(
                number = packet.toULong(),
                sentAtMs = packet.toLong(),
                bytes = 1_000,
                ackEliciting = true
            )
        }

        queue.addLast(stream)
        queue.addFirst(probe)

        assertFalse(stream.canSend(recovery, 1_000))
        assertTrue(probe.canSend(recovery, 1_000))
        assertEquals(probe, queue.removeFirst())
        assertTrue(queue.isNotEmpty)
    }
}
