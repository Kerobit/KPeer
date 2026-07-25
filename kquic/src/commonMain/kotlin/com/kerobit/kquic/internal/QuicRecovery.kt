package com.kerobit.kquic.internal

/** Minimal deterministic RFC 9002 sent-packet ledger. Platform timers feed it monotonic ms. */
internal class QuicRecovery(
    private val packetThreshold: ULong = 3uL,
    private val initialRttMs: Long = 333L
) {
    private data class Sent(val number: ULong, val sentAtMs: Long, val bytes: Int, val ackEliciting: Boolean)
    private val sent = linkedMapOf<ULong, Sent>()
    var bytesInFlight: Long = 0
        private set
    var smoothedRttMs: Long? = null
        private set
    var packetsLost: Long = 0
        private set

    fun onPacketSent(number: ULong, sentAtMs: Long, bytes: Int, ackEliciting: Boolean) {
        require(bytes >= 0)
        check(sent.put(number, Sent(number, sentAtMs, bytes, ackEliciting)) == null) { "Packet number reused" }
        if (ackEliciting) bytesInFlight += bytes
    }

    fun onAckReceived(ranges: List<QuicAckRange>, nowMs: Long, ackDelayMs: Long): List<ULong> {
        require(ackDelayMs >= 0)
        val acked = sent.values.filter { packet -> ranges.any { packet.number in it.start..it.end } }
        if (acked.isEmpty()) return emptyList()
        val largest = acked.maxBy { it.number }
        val sample = (nowMs - largest.sentAtMs - ackDelayMs).coerceAtLeast(0)
        smoothedRttMs = smoothedRttMs?.let { (it * 7 + sample) / 8 } ?: sample
        acked.forEach {
            sent.remove(it.number)
            if (it.ackEliciting) bytesInFlight -= it.bytes
        }
        detectLost(ranges.maxOf { it.end }, nowMs)
        return acked.map { it.number }
    }

    fun lossDeadlineMs(nowMs: Long): Long {
        val rtt = smoothedRttMs ?: initialRttMs
        return nowMs + (rtt * 9 / 8).coerceAtLeast(1)
    }

    private fun detectLost(largestAcked: ULong, nowMs: Long) {
        val timeThreshold = ((smoothedRttMs ?: initialRttMs) * 9 / 8).coerceAtLeast(1)
        val lost = sent.values.filter {
            (largestAcked >= it.number + packetThreshold) || nowMs - it.sentAtMs >= timeThreshold
        }
        lost.forEach {
            sent.remove(it.number)
            if (it.ackEliciting) bytesInFlight -= it.bytes
            packetsLost++
        }
    }
}
