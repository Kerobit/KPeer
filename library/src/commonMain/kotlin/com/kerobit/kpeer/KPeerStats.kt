package com.kerobit.kpeer

/**
 * Cross-platform WebRTC stats snapshot.
 *
 * The top-level shape (id/type/timestamp + values) is stable across platforms.
 * The concrete keys inside [values] are platform/WebRTC-implementation dependent.
 */
data class KPeerStatsReport(
    val stats: List<KPeerStat>
)

data class KPeerStat(
    val id: String,
    val type: String,
    /** Timestamp in microseconds (as exposed by the platform WebRTC implementation). */
    val timestampUs: Long,
    val values: Map<String, KPeerStatValue>
)

sealed interface KPeerStatValue {
    data class Str(val value: String) : KPeerStatValue
    data class Num(val value: Double) : KPeerStatValue
    data class Bool(val value: Boolean) : KPeerStatValue
    data object Null : KPeerStatValue
}

data class KPeerNetworkStats(
    val selectedCandidatePairId: String = "",
    val connectionMode: String = "",
    val rttMs: Long = 0,
    val jitterMs: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val packetsLost: Long = 0,
    val bytesSent: Long = 0,
    val bytesReceived: Long = 0,
    val relayBytes: Long = 0,
)

fun KPeerStatsReport.toNetworkStats(): KPeerNetworkStats {
    val transport = stats.firstOrNull { stat ->
        stat.type.equals("transport", ignoreCase = true) &&
            stat.str("selectedCandidatePairId").isNotEmpty()
    } ?: stats.firstOrNull { it.type.equals("transport", ignoreCase = true) }
    val selectedPairId = transport?.str("selectedCandidatePairId").orEmpty()
    val selectedPair = stats.firstOrNull { it.id == selectedPairId }?.takeIf { it.isCandidatePair() }
        ?: stats.firstOrNull { stat ->
            stat.isCandidatePair() && (stat.bool("selected") || stat.bool("nominated"))
        }
        ?: stats.firstOrNull { stat ->
            stat.isCandidatePair() && stat.str("state").equals("succeeded", ignoreCase = true)
        }

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

    return KPeerNetworkStats(
        selectedCandidatePairId = selectedPair?.id.orEmpty(),
        connectionMode = when {
            selectedPair == null -> ""
            usesRelay -> "relay"
            else -> "direct"
        },
        rttMs = secondsToMs(rttSeconds),
        jitterMs = secondsToMs(selectedPair?.num("jitter") ?: 0.0),
        packetsSent = counterSource?.long("packetsSent") ?: 0,
        packetsReceived = counterSource?.long("packetsReceived") ?: 0,
        packetsLost = counterSource?.long("packetsLost") ?: 0,
        bytesSent = counterSource?.long("bytesSent") ?: 0,
        bytesReceived = counterSource?.long("bytesReceived") ?: 0,
        relayBytes = if (usesRelay) {
            (counterSource?.long("bytesSent") ?: 0) + (counterSource?.long("bytesReceived") ?: 0)
        } else {
            0
        },
    )
}

private fun KPeerStat.isCandidatePair(): Boolean =
    type.equals("candidate-pair", ignoreCase = true) ||
        type.equals("candidate_pair", ignoreCase = true)

private fun KPeerStat?.isRelayCandidate(): Boolean =
    this?.str("candidateType")?.equals("relay", ignoreCase = true) == true

private fun KPeerStat.long(key: String): Long = num(key).toLong().coerceAtLeast(0)
private fun KPeerStat.num(key: String): Double = (values[key] as? KPeerStatValue.Num)?.value ?: 0.0
private fun KPeerStat.str(key: String): String = (values[key] as? KPeerStatValue.Str)?.value.orEmpty()
private fun KPeerStat.bool(key: String): Boolean = (values[key] as? KPeerStatValue.Bool)?.value ?: false
private fun secondsToMs(value: Double): Long = (value * 1000).toLong().coerceAtLeast(0)
