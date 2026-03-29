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

