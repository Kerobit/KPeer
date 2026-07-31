package com.kerobit.kquic.internal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuicPacerTest {
    @Test
    fun initial_burst_is_available_then_credit_refills() {
        val pacer = QuicPacer(initialRttMs = 100, maximumDatagramSize = 1_000)
        repeat(10) {
            assertEquals(0, pacer.delayMs(0, 1_000, 10_000, null))
            pacer.onPacketSent(0, 1_000, 10_000, null)
        }

        assertEquals(8, pacer.delayMs(0, 1_000, 10_000, null))
        assertEquals(0, pacer.delayMs(8, 1_000, 10_000, null))
    }

    @Test
    fun faster_rtt_and_larger_window_reduce_the_next_deadline() {
        val slow = QuicPacer(initialRttMs = 100, maximumDatagramSize = 1_000)
        val fast = QuicPacer(initialRttMs = 100, maximumDatagramSize = 1_000)
        repeat(10) {
            slow.onPacketSent(0, 1_000, 10_000, 100)
            fast.onPacketSent(0, 1_000, 20_000, 50)
        }

        val slowDelay = slow.delayMs(0, 1_000, 10_000, 100)
        val fastDelay = fast.delayMs(0, 1_000, 20_000, 50)

        assertTrue(fastDelay < slowDelay)
        assertEquals(8, slowDelay)
        assertEquals(2, fastDelay)
    }
}
