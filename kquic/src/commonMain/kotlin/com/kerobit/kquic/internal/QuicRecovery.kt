package com.kerobit.kquic.internal

internal data class QuicRecoveryResult(
    val acknowledgedPackets: List<ULong>,
    val lostPackets: List<ULong>,
    val framesToRetransmit: List<QuicFrame>
)

/**
 * Deterministic RFC 9002 loss detector and NewReno congestion controller.
 *
 * One instance belongs to one packet-number space. Callers keep Initial, Handshake and
 * Application Data ledgers separate as required by QUIC.
 */
internal class QuicRecovery(
    private val packetThreshold: ULong = 3uL,
    private val initialRttMs: Long = 333L,
    private val maximumDatagramSize: Int = 1_200,
    private val timerGranularityMs: Long = 1L
) {
    private data class Sent(
        val number: ULong,
        val sentAtMs: Long,
        val bytes: Int,
        val ackEliciting: Boolean,
        val retransmittableFrames: List<QuicFrame>
    )
    private val sent = linkedMapOf<ULong, Sent>()
    var bytesInFlight: Long = 0
        private set
    var congestionWindow: Long = minOf(
        10L * maximumDatagramSize,
        maxOf(2L * maximumDatagramSize, 14_720L)
    )
        private set
    var slowStartThreshold: Long = Long.MAX_VALUE
        private set
    var latestRttMs: Long? = null
        private set
    var minimumRttMs: Long? = null
        private set
    var smoothedRttMs: Long? = null
        private set
    var rttVarianceMs: Long? = null
        private set
    var packetsLost: Long = 0
        private set
    var ptoCount: Int = 0
        private set
    private var recoveryStartedAtMs: Long? = null

    fun onPacketSent(
        number: ULong,
        sentAtMs: Long,
        bytes: Int,
        ackEliciting: Boolean,
        retransmittableFrames: List<QuicFrame> = emptyList()
    ) {
        require(bytes >= 0)
        check(sent.put(
            number,
            Sent(number, sentAtMs, bytes, ackEliciting, retransmittableFrames)
        ) == null) { "Packet number reused" }
        if (ackEliciting) bytesInFlight += bytes
    }

    fun onAckReceived(ranges: List<QuicAckRange>, nowMs: Long, ackDelayMs: Long): List<ULong> {
        return onAckReceivedDetailed(ranges, nowMs, ackDelayMs).acknowledgedPackets
    }

    fun onAckReceivedDetailed(
        ranges: List<QuicAckRange>,
        nowMs: Long,
        ackDelayMs: Long
    ): QuicRecoveryResult {
        require(ackDelayMs >= 0)
        val acked = sent.values.filter { packet -> ranges.any { packet.number in it.start..it.end } }
        if (acked.isEmpty()) return QuicRecoveryResult(emptyList(), emptyList(), emptyList())
        val largest = acked.maxBy { it.number }
        updateRtt(nowMs - largest.sentAtMs, ackDelayMs)
        var congestionAckedBytes = 0L
        acked.forEach {
            sent.remove(it.number)
            if (it.ackEliciting) {
                bytesInFlight -= it.bytes
                if (recoveryStartedAtMs?.let { recoveryStart -> it.sentAtMs <= recoveryStart } != true) {
                    congestionAckedBytes += it.bytes
                }
            }
        }
        increaseCongestionWindow(congestionAckedBytes)
        val lost = detectLost(ranges.maxOf { it.end }, nowMs)
        if (acked.any(Sent::ackEliciting)) ptoCount = 0
        return QuicRecoveryResult(
            acknowledgedPackets = acked.map { it.number },
            lostPackets = lost.map { it.number },
            framesToRetransmit = lost.flatMap { it.retransmittableFrames }
        )
    }

    fun canSend(bytes: Int): Boolean {
        require(bytes >= 0)
        return bytesInFlight + bytes <= congestionWindow
    }

    fun lossDeadlineMs(nowMs: Long, maxAckDelayMs: Long = 0): Long {
        val earliestOutstanding = sent.values
            .filter(Sent::ackEliciting)
            .minOfOrNull(Sent::sentAtMs)
            ?: return Long.MAX_VALUE
        val baseRtt = smoothedRttMs ?: initialRttMs
        val variance = rttVarianceMs ?: (initialRttMs / 2)
        val pto = baseRtt + maxOf(4 * variance, timerGranularityMs) + maxAckDelayMs
        val multiplier = 1L shl ptoCount.coerceAtMost(20)
        return maxOf(nowMs, earliestOutstanding + pto * multiplier)
    }

    fun onPtoExpired() {
        ptoCount = (ptoCount + 1).coerceAtMost(20)
    }

    /** Frames for a single PTO probe, oldest outstanding packet first. */
    fun ptoProbeFrames(): List<QuicFrame> =
        sent.values
            .firstOrNull { it.ackEliciting && it.retransmittableFrames.isNotEmpty() }
            ?.retransmittableFrames
            .orEmpty()

    private fun updateRtt(rawSampleMs: Long, ackDelayMs: Long) {
        val latest = rawSampleMs.coerceAtLeast(0)
        latestRttMs = latest
        minimumRttMs = minimumRttMs?.let { minOf(it, latest) } ?: latest
        val adjusted = if (latest - (minimumRttMs ?: latest) >= ackDelayMs) {
            latest - ackDelayMs
        } else {
            latest
        }
        val previous = smoothedRttMs
        if (previous == null) {
            smoothedRttMs = adjusted
            rttVarianceMs = adjusted / 2
        } else {
            val deviation = absoluteDifference(previous, adjusted)
            rttVarianceMs = ((rttVarianceMs ?: deviation) * 3 + deviation) / 4
            smoothedRttMs = (previous * 7 + adjusted) / 8
        }
    }

    private fun increaseCongestionWindow(acknowledgedBytes: Long) {
        if (acknowledgedBytes == 0L) return
        congestionWindow += if (congestionWindow < slowStartThreshold) {
            acknowledgedBytes
        } else {
            (maximumDatagramSize.toLong() * acknowledgedBytes / congestionWindow).coerceAtLeast(1)
        }
    }

    private fun detectLost(largestAcked: ULong, nowMs: Long): List<Sent> {
        val timeThreshold = (
            maxOf(latestRttMs ?: initialRttMs, smoothedRttMs ?: initialRttMs) * 9 / 8
            ).coerceAtLeast(timerGranularityMs)
        val lost = sent.values.filter {
            (largestAcked >= it.number + packetThreshold) || nowMs - it.sentAtMs >= timeThreshold
        }
        if (lost.any(Sent::ackEliciting)) {
            val newestLost = lost.maxOf(Sent::sentAtMs)
            if (recoveryStartedAtMs?.let { newestLost <= it } != true) {
                recoveryStartedAtMs = nowMs
                congestionWindow = maxOf(congestionWindow / 2, 2L * maximumDatagramSize)
                slowStartThreshold = congestionWindow
            }
        }
        lost.forEach {
            sent.remove(it.number)
            if (it.ackEliciting) bytesInFlight -= it.bytes
            packetsLost++
        }
        return lost
    }

    private fun absoluteDifference(first: Long, second: Long): Long =
        if (first >= second) first - second else second - first
}

internal enum class QuicPacketNumberSpace {
    INITIAL,
    HANDSHAKE,
    APPLICATION_DATA
}

internal class QuicRecoverySpaces(
    private val create: () -> QuicRecovery = { QuicRecovery() }
) {
    private val spaces = QuicPacketNumberSpace.entries.associateWith { create() }.toMutableMap()

    operator fun get(space: QuicPacketNumberSpace): QuicRecovery =
        checkNotNull(spaces[space])

    fun discard(space: QuicPacketNumberSpace) {
        spaces[space] = create()
    }
}
