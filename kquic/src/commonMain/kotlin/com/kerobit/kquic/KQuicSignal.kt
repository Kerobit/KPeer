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

    override fun toString(): String = if (type == KQuicCandidateType.RELAY) {
        "KQuicCandidate(foundation=$foundation, type=$type, address=<redacted>)"
    } else {
        "KQuicCandidate(foundation=$foundation, type=$type, host=$host, port=$port, " +
            "priority=$priority, relatedHost=$relatedHost, relatedPort=$relatedPort)"
    }
}

/** A local/remote candidate pair selected after connectivity checks. */
data class KQuicCandidatePair(val local: KQuicCandidate, val remote: KQuicCandidate)

/** Signals are transported by the app; KQuic never opens a signaling connection itself. */
sealed interface KQuicSignal {
    val rendezvousId: String
}

/**
 * Short-lived credentials used only for authenticated peer connectivity checks.
 * They are transported over the application's authenticated signaling channel.
 */
data class KQuicConnectivityCredentials(
    val usernameFragment: String,
    val password: String
) {
    init {
        require(usernameFragment.length in 4..256)
        require(password.length in 16..256)
    }

    override fun toString(): String =
        "KQuicConnectivityCredentials(usernameFragment=$usernameFragment, password=<redacted>)"
}

data class KQuicOffer(
    override val rendezvousId: String,
    val candidates: List<KQuicCandidate>,
    val identityPublicKeySha256: String,
    val connectivityCredentials: KQuicConnectivityCredentials,
    val endOfCandidates: Boolean = false
) : KQuicSignal {
    init {
        require(identityPublicKeySha256.isSha256Hex())
    }

    override fun toString(): String =
        "KQuicOffer(rendezvousId=$rendezvousId, candidates=$candidates, " +
            "identityPublicKeySha256=${identityPublicKeySha256.take(8)}…, " +
            "connectivityCredentials=$connectivityCredentials, endOfCandidates=$endOfCandidates)"
}

data class KQuicAnswer(
    override val rendezvousId: String,
    val candidates: List<KQuicCandidate>,
    val identityPublicKeySha256: String,
    val connectivityCredentials: KQuicConnectivityCredentials,
    val endOfCandidates: Boolean = false
) : KQuicSignal {
    init {
        require(identityPublicKeySha256.isSha256Hex())
    }

    override fun toString(): String =
        "KQuicAnswer(rendezvousId=$rendezvousId, candidates=$candidates, " +
            "identityPublicKeySha256=${identityPublicKeySha256.take(8)}…, " +
            "connectivityCredentials=$connectivityCredentials, endOfCandidates=$endOfCandidates)"
}

data class KQuicCandidateUpdate(
    override val rendezvousId: String,
    /** Complete snapshot, so losing an earlier trickle update cannot lose a candidate. */
    val candidates: List<KQuicCandidate>,
    val endOfCandidates: Boolean = false
) : KQuicSignal

data class KQuicEndOfCandidates(
    override val rendezvousId: String
) : KQuicSignal
