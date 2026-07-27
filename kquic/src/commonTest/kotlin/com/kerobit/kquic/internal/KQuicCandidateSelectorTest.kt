package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicCandidate
import com.kerobit.kquic.KQuicCandidateType
import kotlin.test.Test
import kotlin.test.assertEquals

class KQuicCandidateSelectorTest {
    @Test
    fun equal_priority_pairs_have_the_same_reciprocal_order_for_both_roles() {
        val left = listOf(
            candidate("left-a", "10.0.0.1", 41_001),
            candidate("left-b", "10.0.1.1", 41_002)
        )
        val right = listOf(
            candidate("right-a", "10.0.0.2", 42_001),
            candidate("right-b", "10.0.1.2", 42_002)
        )
        val leftOrder = KQuicCandidateSelector.orderedPairs(left, right)
            .map { endpoints(it.local, it.remote) }
        val rightOrder = KQuicCandidateSelector.orderedPairs(right, left)
            .map { endpoints(it.local, it.remote) }

        assertEquals(leftOrder, rightOrder)
    }

    private fun endpoints(first: KQuicCandidate, second: KQuicCandidate): Set<String> =
        setOf("${first.host}:${first.port}", "${second.host}:${second.port}")

    private fun candidate(foundation: String, host: String, port: Int) = KQuicCandidate(
        foundation = foundation,
        type = KQuicCandidateType.HOST,
        host = host,
        port = port,
        priority = 100
    )
}
