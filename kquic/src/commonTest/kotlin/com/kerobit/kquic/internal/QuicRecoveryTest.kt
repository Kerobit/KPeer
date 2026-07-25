package com.kerobit.kquic.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuicRecoveryTest {
    @Test
    fun ack_updates_flight_and_marks_old_packets_lost() {
        val recovery = QuicRecovery()
        recovery.onPacketSent(1uL, sentAtMs = 0, bytes = 100, ackEliciting = true)
        recovery.onPacketSent(2uL, sentAtMs = 1, bytes = 100, ackEliciting = true)
        recovery.onPacketSent(3uL, sentAtMs = 2, bytes = 100, ackEliciting = true)
        recovery.onPacketSent(4uL, sentAtMs = 3, bytes = 100, ackEliciting = true)

        assertEquals(listOf(4uL), recovery.onAckReceived(listOf(QuicAckRange(4uL, 4uL)), 40, 0))
        assertEquals(200, recovery.bytesInFlight)
        assertEquals(1, recovery.packetsLost)
        assertEquals(37, recovery.smoothedRttMs)
    }

    @Test
    fun ack_tracker_coalesces_ranges_and_requests_immediate_ack_for_reordering() {
        val tracker = QuicAckTracker()
        assertTrue(tracker.onPacketReceived(1uL, 10, ackEliciting = true))
        assertFalse(tracker.shouldAcknowledge())
        assertTrue(tracker.onPacketReceived(3uL, 12, ackEliciting = true))
        assertTrue(tracker.shouldAcknowledge())
        assertFalse(tracker.onPacketReceived(3uL, 13, ackEliciting = true))

        val ack = tracker.frame(nowMs = 14, ackDelayExponent = 3)
        assertEquals(
            listOf(QuicAckRange(3uL, 3uL), QuicAckRange(1uL, 1uL)),
            ack?.ranges
        )
        assertEquals(250uL, ack?.ackDelay)
    }

    @Test
    fun new_reno_reduces_window_on_loss_and_returns_retransmittable_frames() {
        val recovery = QuicRecovery()
        val frame = QuicFrame.Crypto(0uL, byteArrayOf(1))
        recovery.onPacketSent(1uL, 0, 1_200, true, listOf(frame))
        recovery.onPacketSent(2uL, 1, 1_200, true)
        recovery.onPacketSent(3uL, 2, 1_200, true)
        recovery.onPacketSent(4uL, 3, 1_200, true)
        val initialWindow = recovery.congestionWindow
        val result = recovery.onAckReceivedDetailed(
            listOf(QuicAckRange(4uL, 4uL)),
            nowMs = 40,
            ackDelayMs = 0
        )
        assertEquals(listOf(1uL), result.lostPackets)
        assertEquals(listOf(frame), result.framesToRetransmit)
        assertTrue(recovery.congestionWindow < initialWindow)
    }
}
