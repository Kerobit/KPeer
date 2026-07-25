package com.kerobit.kquic.internal

/** Deterministic delayed-ACK state for one packet-number space. */
internal class QuicAckTracker(
    private val maximumRanges: Int = 64,
    private val ackEvery: Int = 2
) {
    private val received = mutableSetOf<ULong>()
    private var ackElicitingSinceLastAck = 0
    private var largestReceivedAtMs = 0L
    private var immediateAck = false

    init {
        require(maximumRanges > 0)
        require(ackEvery > 0)
    }

    /** Returns false for a duplicate packet. */
    fun onPacketReceived(packetNumber: ULong, receivedAtMs: Long, ackEliciting: Boolean): Boolean {
        if (!received.add(packetNumber)) return false
        val previousLargest = received.asSequence().filter { it != packetNumber }.maxOrNull()
        if (previousLargest == null || packetNumber > previousLargest) {
            if (previousLargest != null && packetNumber > previousLargest + 1uL) immediateAck = true
            largestReceivedAtMs = receivedAtMs
        } else {
            immediateAck = true
        }
        if (ackEliciting) ackElicitingSinceLastAck++
        trimOldRanges()
        return true
    }

    fun shouldAcknowledge(): Boolean =
        immediateAck || ackElicitingSinceLastAck >= ackEvery

    fun frame(nowMs: Long, ackDelayExponent: Int): QuicFrame.Ack? {
        if (received.isEmpty()) return null
        require(ackDelayExponent in 0..20)
        val ranges = ranges()
        val delayMicros = ((nowMs - largestReceivedAtMs).coerceAtLeast(0) * 1_000L).toULong()
        val encodedDelay = delayMicros shr ackDelayExponent
        ackElicitingSinceLastAck = 0
        immediateAck = false
        return QuicFrame.Ack(ranges, encodedDelay)
    }

    private fun ranges(): List<QuicAckRange> {
        val descending = received.sortedDescending()
        val result = mutableListOf<QuicAckRange>()
        var end = descending.first()
        var start = end
        descending.drop(1).forEach { packet ->
            if (packet + 1uL == start) {
                start = packet
            } else {
                result += QuicAckRange(start, end)
                start = packet
                end = packet
            }
        }
        result += QuicAckRange(start, end)
        return result.take(maximumRanges)
    }

    private fun trimOldRanges() {
        val ranges = ranges()
        if (ranges.size < maximumRanges) return
        val oldestKept = ranges.last().start
        received.removeAll { it < oldestKept }
    }
}
