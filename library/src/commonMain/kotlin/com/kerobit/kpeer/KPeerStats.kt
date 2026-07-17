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
    val selectedPair = stats.firstOrNull { stat ->
        stat.type.contains("candidate-pair", ignoreCase = true) &&
            (stat.bool("selected") || stat.bool("nominated") || stat.str("state") == "succeeded")
    } ?: stats.firstOrNull { it.type.contains("candidate-pair", ignoreCase = true) }

    val allBytesSent = stats.sumOf { it.long("bytesSent") }
    val allBytesReceived = stats.sumOf { it.long("bytesReceived") }
    val packetsSent = stats.sumOf { it.long("packetsSent") }
    val packetsReceived = stats.sumOf { it.long("packetsReceived") }
    val packetsLost = stats.sumOf { it.long("packetsLost") }
    val jitterMs = stats.maxOfOrNull { secondsToMs(it.num("jitter")) } ?: 0
    val rttMs = selectedPair?.let {
        secondsToMs(it.num("currentRoundTripTime").takeIf { value -> value > 0.0 } ?: it.num("totalRoundTripTime"))
    } ?: 0
    val relayBytes = stats
        .filter { stat -> stat.type.contains("candidate", ignoreCase = true) && stat.str("candidateType") == "relay" }
        .sumOf { it.long("bytesSent") + it.long("bytesReceived") }

    return KPeerNetworkStats(
        selectedCandidatePairId = selectedPair?.id.orEmpty(),
        connectionMode = if (relayBytes > 0) "relay" else if (selectedPair != null) "direct" else "",
        rttMs = rttMs,
        jitterMs = jitterMs,
        packetsSent = packetsSent,
        packetsReceived = packetsReceived,
        packetsLost = packetsLost,
        bytesSent = allBytesSent,
        bytesReceived = allBytesReceived,
        relayBytes = relayBytes,
    )
}

private fun KPeerStat.long(key: String): Long = num(key).toLong().coerceAtLeast(0)
private fun KPeerStat.num(key: String): Double = (values[key] as? KPeerStatValue.Num)?.value ?: 0.0
private fun KPeerStat.str(key: String): String = (values[key] as? KPeerStatValue.Str)?.value.orEmpty()
private fun KPeerStat.bool(key: String): Boolean = (values[key] as? KPeerStatValue.Bool)?.value ?: false
private fun secondsToMs(value: Double): Long = (value * 1000).toLong().coerceAtLeast(0)
