package com.kerobit.kquic

import kotlinx.coroutines.flow.Flow

/** Marks APIs whose wire compatibility is not stable until the first KQuic release. */
@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
annotation class ExperimentalKQuicApi

/** Credentials consumed by the TLS 1.3 handshake. Their issuance is owned by the integrator. */
data class KQuicCredentials(
    val certificateChainDer: List<ByteArray>,
    val privateKeyHandle: String
)

data class KQuicHandshakeRequest(
    val rendezvousId: String,
    val isInitiator: Boolean
)

data class KQuicRemoteIdentity(
    val certificateChainDer: List<ByteArray>,
    val serverName: String? = null
)

sealed interface KQuicVerificationResult {
    data object Accepted : KQuicVerificationResult
    data class Rejected(val reason: String) : KQuicVerificationResult
}

/** Supplies TLS material without coupling KQuic to an identity or matchmaking system. */
fun interface KQuicCredentialsProvider {
    suspend fun credentialsFor(request: KQuicHandshakeRequest): KQuicCredentials
}

/** Decides whether the certificate chain presented by the remote QUIC peer is trusted. */
fun interface KQuicPeerVerifier {
    suspend fun verify(identity: KQuicRemoteIdentity): KQuicVerificationResult
}

/** Produces host, server-reflexive, or relay candidates as they become available. */
fun interface KQuicCandidateSource {
    fun candidates(): Flow<KQuicCandidate>
}

data class KQuicLimits(
    val maxBidirectionalStreams: Long = 64,
    val maxUnidirectionalStreams: Long = 64,
    val maxConnectionData: Long = 1_048_576,
    val maxStreamData: Long = 262_144,
    val maxMessageSize: Int = 262_144,
    val maxChannelLabelBytes: Int = 256,
    val maxDatagramSize: Int = 1_200,
    val maxBufferedPackets: Int = 1_024
) {
    init {
        require(maxBidirectionalStreams >= 0)
        require(maxUnidirectionalStreams >= 0)
        require(maxConnectionData > 0)
        require(maxStreamData > 0)
        require(maxMessageSize > 0)
        require(maxChannelLabelBytes > 0)
        require(maxDatagramSize in 1..65_507)
        require(maxBufferedPackets > 0)
    }
}

/**
 * Settings for an individual P2P QUIC connection.
 *
 * [initiator] selects the signaling role. Both peers still gather candidates and run
 * connectivity checks, so it is not equivalent to a WebRTC offer SDP role.
 */
data class KQuicConfig(
    val initiator: Boolean,
    val rendezvousId: String,
    val credentialsProvider: KQuicCredentialsProvider,
    val peerVerifier: KQuicPeerVerifier,
    val candidateSources: List<KQuicCandidateSource> = emptyList(),
    val initialCandidates: List<KQuicCandidate> = emptyList(),
    val limits: KQuicLimits = KQuicLimits(),
    val connectionTimeoutMs: Long? = 30_000,
    val idleTimeoutMs: Long = 30_000
) {
    init {
        require(rendezvousId.isNotBlank())
        require(connectionTimeoutMs == null || connectionTimeoutMs > 0)
        require(idleTimeoutMs > 0)
    }
}
