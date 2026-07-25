package com.kerobit.kquic

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalKQuicApi::class)
class KQuicPeerTest {
    private val credentials = KQuicCredentialsProvider {
        KQuicCredentials(certificateChainDer = emptyList(), privateKeyHandle = "test")
    }
    private val verifier = KQuicPeerVerifier { KQuicVerificationResult.Accepted }

    @Test
    fun remote_candidates_move_peer_to_connecting() = runBlocking {
        val peer = KQuicPeer(
            KQuicContext(this),
            KQuicConfig(
                initiator = true,
                rendezvousId = "room-1",
                credentialsProvider = credentials,
                peerVerifier = verifier,
                initialCandidates = listOf(candidate("local", KQuicCandidateType.HOST, 10))
            )
        )
        peer.start()
        peer.signal(KQuicAnswer("room-1", listOf(candidate("remote", KQuicCandidateType.RELAY, 1))))
        assertEquals(KQuicPeerConnectionState.CONNECTING, peer.currentConnectionState)
        peer.close()
    }

    @Test
    fun signal_from_other_rendezvous_is_rejected() = runBlocking {
        val peer = KQuicPeer(
            KQuicContext(this),
            KQuicConfig(false, "room-1", credentials, verifier)
        )
        assertFailsWith<KQuicError.InvalidSignal> {
            peer.signal(KQuicOffer("room-2", emptyList()))
        }
        peer.close()
    }

    private fun candidate(foundation: String, type: KQuicCandidateType, priority: Long) = KQuicCandidate(
        foundation = foundation,
        type = type,
        host = "127.0.0.1",
        port = 5000,
        priority = priority,
        relayToken = if (type == KQuicCandidateType.RELAY) "opaque" else null
    )
}
