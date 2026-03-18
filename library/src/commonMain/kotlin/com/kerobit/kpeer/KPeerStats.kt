package com.kerobit.kpeer

/**
 * Cross-platform WebRTC stats snapshot.
 *
 * The top-level shape (id/type/timestamp + values) is stable across platforms.
 * The concrete keys inside [values] are platform/WebRTC-implementation dependent.
 */
public data class KPeerStatsReport(
    public val stats: List<KPeerStat>
)

public data class KPeerStat(
    public val id: String,
    public val type: String,
    /** Timestamp in microseconds (as exposed by the platform WebRTC implementation). */
    public val timestampUs: Long,
    public val values: Map<String, KPeerStatValue>
)

public sealed interface KPeerStatValue {
    public data class Str(val value: String) : KPeerStatValue
    public data class Num(val value: Double) : KPeerStatValue
    public data class Bool(val value: Boolean) : KPeerStatValue
    public data object Null : KPeerStatValue
}

