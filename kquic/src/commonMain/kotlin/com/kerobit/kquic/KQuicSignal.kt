package com.kerobit.kquic

/** Candidate families used by KQuic's ICE-compatible rendezvous layer. */
enum class KQuicCandidateType { HOST, SERVER_REFLEXIVE, RELAY }

/**
 * An address advertised through the application's signaling channel.
 * Relay allocation tokens are opaque to KQuic and must not be logged.
 */
data class KQuicCandidate(
    val foundation: String,
    val type: KQuicCandidateType,
    val host: String,
    val port: Int,
    val priority: Long,
    val relatedHost: String? = null,
    val relatedPort: Int? = null,
    val relayToken: String? = null
) {
    init {
        require(foundation.isNotBlank())
        require(host.isNotBlank())
        require(port in 1..65_535)
        require(priority >= 0)
        require((relatedHost == null) == (relatedPort == null))
        require(type != KQuicCandidateType.RELAY || !relayToken.isNullOrBlank())
    }
}

/** A local/remote candidate pair selected after connectivity checks. */
data class KQuicCandidatePair(
    val local: KQuicCandidate,
    val remote: KQuicCandidate
)

/** Signals are transported by the app; KQuic never opens a signaling connection itself. */
sealed interface KQuicSignal {
    val rendezvousId: String
}

data class KQuicOffer(
    override val rendezvousId: String,
    val candidates: List<KQuicCandidate>
) : KQuicSignal

data class KQuicAnswer(
    override val rendezvousId: String,
    val candidates: List<KQuicCandidate>
) : KQuicSignal

data class KQuicCandidateUpdate(
    override val rendezvousId: String,
    val candidate: KQuicCandidate
) : KQuicSignal

data class KQuicEndOfCandidates(
    override val rendezvousId: String
) : KQuicSignal
