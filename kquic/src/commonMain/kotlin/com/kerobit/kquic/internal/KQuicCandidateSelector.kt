package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicCandidate
import com.kerobit.kquic.KQuicCandidatePair
import com.kerobit.kquic.KQuicCandidateType

/**
 * Deterministic candidate ordering. Connectivity checks still decide the usable path;
 * ranking merely defines the order in which checks are scheduled.
 */
internal object KQuicCandidateSelector {
    fun orderedPairs(
        local: Collection<KQuicCandidate>,
        remote: Collection<KQuicCandidate>
    ): List<KQuicCandidatePair> = local.flatMap { left ->
        remote.map { right -> KQuicCandidatePair(left, right) }
    }.sortedWith(
        compareByDescending<KQuicCandidatePair> { pairScore(it) }
            .thenBy { it.local.foundation }
            .thenBy { it.remote.foundation }
    )

    private fun pairScore(pair: KQuicCandidatePair): Long {
        val typeBonus = pair.local.type.preference + pair.remote.type.preference
        return typeBonus * 1_000_000_000L + pair.local.priority + pair.remote.priority
    }

    private val KQuicCandidateType.preference: Long
        get() = when (this) {
            KQuicCandidateType.HOST -> 3
            KQuicCandidateType.SERVER_REFLEXIVE -> 2
            KQuicCandidateType.RELAY -> 1
        }
}
