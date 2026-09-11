package com.kerobit.kpeer

/**
 * Cross-platform WebRTC stats snapshot.
 *
 * The top-level shape (id/type/timestamp + values) is stable across platforms.
 * The concrete keys inside [values] are platform/WebRTC-implementation dependent.
 */
data class KPeerStatsReport(
    val stats: List<KPeerStat>,
) {
    /**
     * Active ICE/transport path: selected candidate pair, direct vs relay, and path counters.
     *
     * Counters come from that selected pair (or the transport as a fallback). They are not summed
     * across the full report because transport and candidate-pair describe the same traffic.
     */
    val pathStats: KPeerPathStats
        get() {
            val transport = stats.firstOrNull { stat ->
                stat.typeEquals("transport") && stat.str("selectedCandidatePairId").isNotEmpty()
            } ?: stats.firstOrNull { it.typeEquals("transport") }
            val selectedPairId = transport?.str("selectedCandidatePairId").orEmpty()
            val selectedPair = stats.firstOrNull { it.id == selectedPairId }?.takeIf { it.isCandidatePair }
                ?: stats.firstOrNull { it.isCandidatePair && (it.bool("selected") || it.bool("nominated")) }
                ?: stats.firstOrNull { it.isCandidatePair && it.str("state").equals("succeeded", ignoreCase = true) }

            // Candidate-pair and transport reports describe the same underlying traffic. Prefer the
            // selected pair and use the transport only as a platform fallback; summing the whole report
            // would double count bytes and packets when both objects are present.
            val counterSource = selectedPair ?: transport
            val localCandidate = selectedPair
                ?.str("localCandidateId")
                ?.takeIf(String::isNotEmpty)
                ?.let { id -> stats.firstOrNull { it.id == id } }
            val remoteCandidate = selectedPair
                ?.str("remoteCandidateId")
                ?.takeIf(String::isNotEmpty)
                ?.let { id -> stats.firstOrNull { it.id == id } }
            val localCandidateType = localCandidate?.str("candidateType").orEmpty()
            val remoteCandidateType = remoteCandidate?.str("candidateType").orEmpty()
            val usesRelay = localCandidate.isRelayCandidate() || remoteCandidate.isRelayCandidate()
            val currentRttSeconds = selectedPair?.num("currentRoundTripTime") ?: 0.0
            val responsesReceived = selectedPair?.long("responsesReceived") ?: 0L
            val averageRttSeconds = if (responsesReceived > 0) {
                (selectedPair?.num("totalRoundTripTime") ?: 0.0) / responsesReceived.toDouble()
            } else {
                0.0
            }
            val rttSeconds = currentRttSeconds.takeIf { it > 0.0 }
                ?: averageRttSeconds.takeIf { it > 0.0 }
                ?: 0.0

            return KPeerPathStats(
                selectedCandidatePairId = selectedPair?.id.orEmpty(),
                connectionMode = when {
                    selectedPair == null -> ""
                    usesRelay -> "relay"
                    else -> "direct"
                },
                localCandidateType = localCandidateType,
                remoteCandidateType = remoteCandidateType,
                rttMs = secondsToMs(rttSeconds),
                packetsSent = counterSource?.long("packetsSent") ?: 0,
                packetsReceived = counterSource?.long("packetsReceived") ?: 0,
                bytesSent = counterSource?.long("bytesSent") ?: 0,
                bytesReceived = counterSource?.long("bytesReceived") ?: 0,
                relayBytes = if (usesRelay) {
                    (counterSource?.long("bytesSent") ?: 0) + (counterSource?.long("bytesReceived") ?: 0)
                } else {
                    0
                },
            )
        }

    /**
     * Connection-quality counters (jitter, loss, and related byte/packet totals).
     *
     * Sourced from inbound/outbound RTP report objects when the platform exposes them. This is not
     * A/V "media" semantics: KPeer is data-channel oriented, and these fields describe transport
     * quality signals available in the WebRTC stats graph.
     */
    val qualityStats: KPeerQualityStats
        get() {
            val inbound = stats.filter { it.isInboundRtp }
            val outbound = stats.filter { it.isOutboundRtp }
            return KPeerQualityStats(
                jitterMs = inbound.maxOfOrNull { secondsToMs(it.num("jitter")) } ?: 0,
                packetsLost = inbound.sumOf { it.long("packetsLost") },
                packetsSent = outbound.sumOf { it.long("packetsSent") },
                packetsReceived = inbound.sumOf { it.long("packetsReceived") },
                bytesSent = outbound.sumOf { it.long("bytesSent") },
                bytesReceived = inbound.sumOf { it.long("bytesReceived") },
            )
        }

    /** Both structured views from this report. */
    val normalizedStats: KPeerNormalizedStats
        get() = KPeerNormalizedStats(path = pathStats, quality = qualityStats)

    private fun KPeerStat?.isRelayCandidate(): Boolean =
        this?.str("candidateType")?.equals("relay", ignoreCase = true) == true

    private fun secondsToMs(value: Double): Long = (value * 1000).toLong().coerceAtLeast(0)
}

data class KPeerStat(
    val id: String,
    val type: String,
    /** Timestamp in microseconds (as exposed by the platform WebRTC implementation). */
    val timestampUs: Long,
    val values: Map<String, KPeerStatValue>,
) {
    internal val isCandidatePair: Boolean
        get() = typeEquals("candidate-pair") || typeEquals("candidate_pair")

    internal val isInboundRtp: Boolean
        get() = typeEquals("inbound-rtp") || typeEquals("inbound_rtp")

    internal val isOutboundRtp: Boolean
        get() = typeEquals("outbound-rtp") || typeEquals("outbound_rtp")

    internal fun typeEquals(expected: String): Boolean = type.equals(expected, ignoreCase = true)

    internal fun long(key: String): Long = num(key).toLong().coerceAtLeast(0)

    internal fun num(key: String): Double = (values[key] as? KPeerStatValue.Num)?.value ?: 0.0

    internal fun str(key: String): String = (values[key] as? KPeerStatValue.Str)?.value.orEmpty()

    internal fun bool(key: String): Boolean = (values[key] as? KPeerStatValue.Bool)?.value ?: false
}

sealed interface KPeerStatValue {
    data class Str(val value: String) : KPeerStatValue
    data class Num(val value: Double) : KPeerStatValue
    data class Bool(val value: Boolean) : KPeerStatValue
    data object Null : KPeerStatValue
}

/** Active ICE/transport path snapshot for the selected candidate pair. */
data class KPeerPathStats(
    val selectedCandidatePairId: String = "",
    val connectionMode: String = "",
    val localCandidateType: String = "",
    val remoteCandidateType: String = "",
    val rttMs: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val relayBytes: Long = 0,
)

/** Connection-quality snapshot (jitter, loss, and related counters). */
data class KPeerQualityStats(
    val jitterMs: Long = 0,
    val packetsLost: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
)

/** Convenience bundle when an integration wants both structured views from one report. */
data class KPeerNormalizedStats(
    val path: KPeerPathStats = KPeerPathStats(),
    val quality: KPeerQualityStats = KPeerQualityStats(),
)
