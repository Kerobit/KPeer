package com.kerobit.kquic

import kotlinx.coroutines.flow.Flow

/**
 * Boundary between the stable peer facade and the QUIC protocol engine.
 *
 * Packet parsing, TLS, recovery and UDP I/O stay behind this contract. Keeping it internal
 * prevents platform or protocol implementation details from leaking into the public API.
 */
internal interface KQuicTransport {
    val events: Flow<KQuicTransportEvent>

    suspend fun gatherCandidates(): List<KQuicCandidate>

    suspend fun connect(
        candidatePair: KQuicCandidatePair,
        identity: KQuicIdentity,
        role: KQuicTransportRole,
        localCredentials: KQuicConnectivityCredentials,
        remoteCredentials: KQuicConnectivityCredentials
    ): KQuicRemoteIdentity

    fun openChannel(config: KQuicChannelConfig)
    fun send(label: String, payload: ByteArray, text: Boolean): Boolean
    fun closeChannel(label: String)
    fun close()
}

internal enum class KQuicTransportRole {
    CLIENT,
    SERVER
}

internal sealed interface KQuicTransportEvent {
    data class IncomingChannel(val config: KQuicChannelConfig) : KQuicTransportEvent
    data class BytesReceived(val label: String, val payload: ByteArray) : KQuicTransportEvent
    data class TextReceived(val label: String, val text: String) : KQuicTransportEvent
    data class ChannelClosed(val label: String) : KQuicTransportEvent
    data class Disconnected(val reason: String?) : KQuicTransportEvent
    data class Failed(val error: KQuicError) : KQuicTransportEvent
    data class StatsUpdated(val stats: KQuicTransportStats) : KQuicTransportEvent
}

internal data class KQuicTransportStats(
    val bytesSent: Long,
    val bytesReceived: Long,
    val packetsSent: Long,
    val packetsReceived: Long,
    val packetsLost: Long,
    val datagramsDropped: Long,
    val minimumRttMs: Long? = null,
    val smoothedRttMs: Long?,
    val congestionWindowBytes: Long = 0,
    val bytesInFlight: Long,
    val ptoCount: Long = 0,
    val streamsBlocked: Long = 0
)

internal fun interface KQuicTransportFactory {
    fun create(config: KQuicConfig): KQuicTransport
}
