package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicCandidate
import com.kerobit.kquic.KQuicCandidatePair
import com.kerobit.kquic.KQuicError

/**
 * Deterministic candidate-pair behaviour. It owns retry history but performs no I/O and does not
 * know about signaling, sockets or QUIC handshakes.
 */
internal class ConnectivityBehaviour {
    private val attemptedPairs = linkedSetOf<KQuicCandidatePair>()

    var lastError: KQuicError? = null
        private set

    val attemptCount: Int get() = attemptedPairs.size

    fun nextPair(
        localCandidates: Collection<KQuicCandidate>,
        remoteCandidates: Collection<KQuicCandidate>
    ): KQuicCandidatePair? =
        KQuicCandidateSelector.orderedPairs(localCandidates, remoteCandidates)
            .firstOrNull { it !in attemptedPairs }
            ?.also(attemptedPairs::add)

    fun failed(error: KQuicError) {
        lastError = error
    }

    fun hasUntriedPair(
        localCandidates: Collection<KQuicCandidate>,
        remoteCandidates: Collection<KQuicCandidate>
    ): Boolean =
        KQuicCandidateSelector.orderedPairs(localCandidates, remoteCandidates)
            .any { it !in attemptedPairs }
}
