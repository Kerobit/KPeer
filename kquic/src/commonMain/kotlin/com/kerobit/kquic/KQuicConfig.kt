package com.kerobit.kquic

import kotlinx.coroutines.flow.Flow

/** Marks APIs whose wire compatibility is not stable until the first KQuic release. */
@RequiresOptIn(level = RequiresOptIn.Level.WARNING)
annotation class ExperimentalKQuicApi

enum class KQuicSignatureAlgorithm {
    ECDSA_SECP256R1_SHA256
}

/**
 * Performs CertificateVerify without requiring an exportable private key.
 *
 * Android Keystore and Apple Keychain implementations can keep key material in the platform
 * provider and expose only this operation.
 */
fun interface KQuicSigner {
    suspend fun sign(
        algorithm: KQuicSignatureAlgorithm,
        message: ByteArray
    ): ByteArray
}

/** Identity presented by the mutual TLS 1.3 handshake. */
class KQuicIdentity private constructor(
    val certificateChainDer: List<ByteArray>,
    val signer: KQuicSigner,
    internal val cachedPublicKeySha256: String?
) {
    constructor(
        certificateChainDer: List<ByteArray>,
        signer: KQuicSigner
    ) : this(certificateChainDer, signer, null)

    init {
        require(certificateChainDer.isNotEmpty())
        require(cachedPublicKeySha256 == null || cachedPublicKeySha256.isSha256Hex()) {
            "publicKeySha256 must contain 64 lower-case hexadecimal characters"
        }
    }

    internal companion object {
        fun platformGenerated(
            certificateChainDer: List<ByteArray>,
            signer: KQuicSigner,
            publicKeySha256: String
        ): KQuicIdentity = KQuicIdentity(certificateChainDer, signer, publicKeySha256)
    }
}

data class KQuicHandshakeRequest(
    val rendezvousId: String,
    val isInitiator: Boolean
)

data class KQuicRemoteIdentity(
    val certificateChainDer: List<ByteArray>,
    val publicKeySha256: String,
    val serverName: String? = null
) {
    init {
        require(certificateChainDer.isNotEmpty())
        require(publicKeySha256.isSha256Hex()) {
            "publicKeySha256 must contain 64 lower-case hexadecimal characters"
        }
    }
}

sealed interface KQuicVerificationResult {
    data object Accepted : KQuicVerificationResult
    data class Rejected(val reason: String) : KQuicVerificationResult
}

/** Supplies TLS material without coupling KQuic to an identity or matchmaking system. */
fun interface KQuicIdentityProvider {
    suspend fun identityFor(request: KQuicHandshakeRequest): KQuicIdentity
}

/** Decides whether the certificate chain presented by the remote QUIC peer is trusted. */
fun interface KQuicPeerVerifier {
    suspend fun verify(identity: KQuicRemoteIdentity): KQuicVerificationResult
}

sealed interface KQuicSecurity {
    class Explicit(
        val identityProvider: KQuicIdentityProvider,
        val peerVerifier: KQuicPeerVerifier
    ) : KQuicSecurity

    /**
     * Generates an ephemeral platform identity and pins the fingerprint carried by signaling.
     *
     * This mode is secure only when the application's signaling channel is authenticated.
     */
    data object EphemeralAuthenticatedSignaling : KQuicSecurity

    companion object {
        fun ephemeralWithAuthenticatedSignaling(): KQuicSecurity =
            EphemeralAuthenticatedSignaling
    }
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
    val maxBufferedPackets: Int = 1_024,
    val maxBufferedCryptoBytes: Int = 1_048_576,
    val maxBufferedStreamBytes: Int = 262_144,
    val maxSendQueueBytes: Int = 1_048_576
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
        require(maxBufferedCryptoBytes > 0)
        require(maxBufferedStreamBytes > 0)
        require(maxSendQueueBytes > 0)
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
    val security: KQuicSecurity,
    val gatherHostCandidates: Boolean = true,
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

internal fun String.isSha256Hex(): Boolean =
    length == 64 && all { it in '0'..'9' || it in 'a'..'f' }
