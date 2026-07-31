package com.kerobit.kquic.internal

import kotlin.math.ceil

/** Token-bucket pacer for congestion-controlled 1-RTT packets. */
internal class QuicPacer(
    private val initialRttMs: Long = 333L,
    private val maximumDatagramSize: Int = 1_200
) {
    private var budgetBytes = 0.0
    private var lastUpdatedMs: Long? = null

    fun delayMs(nowMs: Long, packetBytes: Int, congestionWindow: Long, smoothedRttMs: Long?): Long {
        require(packetBytes >= 0)
        val rate = rateBytesPerMs(congestionWindow, smoothedRttMs)
        refill(nowMs, rate, burstCapacity(rate))
        if (budgetBytes >= packetBytes) return 0
        return ceil((packetBytes - budgetBytes) / rate).toLong().coerceAtLeast(1)
    }

    fun onPacketSent(nowMs: Long, packetBytes: Int, congestionWindow: Long,
                     smoothedRttMs: Long?) {
        val rate = rateBytesPerMs(congestionWindow, smoothedRttMs)
        refill(nowMs, rate, burstCapacity(rate))
        budgetBytes = (budgetBytes - packetBytes).coerceAtLeast(0.0)
    }

    private fun refill(nowMs: Long, rate: Double, capacity: Double) {
        val previous = lastUpdatedMs
        if (previous == null) {
            budgetBytes = capacity
        } else {
            budgetBytes = (budgetBytes + (nowMs - previous).coerceAtLeast(0) * rate).coerceAtMost(capacity)
        }
        lastUpdatedMs = nowMs
    }

    private fun rateBytesPerMs(congestionWindow: Long, smoothedRttMs: Long?) =
        congestionWindow.coerceAtLeast(maximumDatagramSize.toLong()) * PACING_GAIN /
        (smoothedRttMs ?: initialRttMs).coerceAtLeast(1)

    private fun burstCapacity(rate: Double): Double {
        val minimum = MINIMUM_BURST_PACKETS * maximumDatagramSize.toDouble()
        val maximum = MAXIMUM_BURST_PACKETS * maximumDatagramSize.toDouble()
        val intervalMs = (minimum / rate).coerceIn(TARGET_BURST_MS, MAXIMUM_BURST_MS)
        return (rate * intervalMs).coerceIn(minimum, maximum)
    }
}

private const val PACING_GAIN = 1.25
private const val TARGET_BURST_MS = 2.0
private const val MAXIMUM_BURST_MS = 10.0
private const val MINIMUM_BURST_PACKETS = 10
private const val MAXIMUM_BURST_PACKETS = 256
