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
        recovery.onPacketSent(14uL, 14, 1_000, ackEliciting = true)
        assertEquals(15_000, recovery.bytesInFlight)
        assertTrue(recovery.bytesInFlight > recovery.congestionWindow)
        assertTrue(queue.isNotEmpty)
    }

    @Test
    fun control_frames_precede_stream_data_and_streams_use_round_robin_fifo() {
        val queue = QuicFrameSendQueue()
        fun stream(id: ULong, value: Int) = QuicQueuedFrame(
            QuicFrame.Stream(id, value.toULong(), false, byteArrayOf(value.toByte())),
            65
        )
        val streamZeroFirst = stream(0uL, 1)
        val streamZeroSecond = stream(0uL, 2)
        val streamFour = stream(4uL, 3)
        val control = QuicQueuedFrame(QuicFrame.MaxData(1_024uL), 65)

        queue.addLast(streamZeroFirst)
        queue.addLast(streamZeroSecond)
        queue.addLast(streamFour)
        queue.addLast(control)

        assertEquals(control, queue.removeFirst())
        assertEquals(streamZeroFirst, queue.removeFirst())
        assertEquals(streamFour, queue.removeFirst())
        assertEquals(streamZeroSecond, queue.removeFirst())
        assertFalse(queue.isNotEmpty)
    }

    @Test
    fun returning_a_blocked_stream_frame_preserves_its_turn() {
        val queue = QuicFrameSendQueue()
        val first = QuicQueuedFrame(
            QuicFrame.Stream(0uL, 0uL, false, byteArrayOf(1)),
            65
        )
        val second = QuicQueuedFrame(
            QuicFrame.Stream(4uL, 0uL, false, byteArrayOf(2)),
            65
        )
        queue.addLast(first)
        queue.addLast(second)

        assertEquals(first, queue.removeFirst())
        queue.addFirst(first)

        assertEquals(first, queue.removeFirst())
        assertEquals(second, queue.removeFirst())
    }
}
