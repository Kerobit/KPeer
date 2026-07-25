package com.kerobit.kquic.internal

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
