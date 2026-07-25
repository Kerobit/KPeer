package com.kerobit.kquic

/** Transport counters deliberately independent from WebRTC's RTCStats schema. */
data class KQuicStats(
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val packetsLost: Long = 0,
    val datagramsDropped: Long = 0,
    val minimumRttMs: Long? = null,
    val smoothedRttMs: Long? = null,
    val congestionWindowBytes: Long = 0,
    val bytesInFlight: Long = 0,
    val ptoCount: Long = 0,
    val streamsBlocked: Long = 0,
    val selectedCandidatePair: KQuicCandidatePair? = null,
    val closeReason: String? = null
)
