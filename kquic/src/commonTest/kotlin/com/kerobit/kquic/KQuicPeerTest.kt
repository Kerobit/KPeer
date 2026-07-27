package com.kerobit.kquic

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@OptIn(ExperimentalKQuicApi::class)
class KQuicPeerTest {
    private val identityProvider = KQuicIdentityProvider { testIdentity() }
    private val verifier = KQuicPeerVerifier { KQuicVerificationResult.Accepted }
    private val security = KQuicSecurity.Explicit(identityProvider, verifier)

    @Test
    fun remote_candidates_move_peer_to_connecting() = runBlocking {
        val peer = KQuicPeer(
            KQuicContext(
                scope = this,
                platformContext = null,
                transportFactory = KQuicTransportFactory { PendingTransport }
            ),
            KQuicConfig(
                initiator = true,
                rendezvousId = "room-1",
                security = security,
                gatherHostCandidates = false
            )
        )
        peer.start()
        peer.signal(
            KQuicAnswer(
                "room-1",
                listOf(candidate("remote", KQuicCandidateType.HOST, 1)),
                TEST_FINGERPRINT,
                TEST_CREDENTIALS
            )
        )
        withTimeout(1_000) {
            peer.connectionState.first { it == KQuicPeerConnectionState.CONNECTING }
        }
        peer.close()
    }

    @Test
    fun relay_candidates_are_rejected_by_the_direct_only_prototype() = runBlocking {
        val peer = KQuicPeer(
            KQuicContext(
                scope = this,
                platformContext = null,
                transportFactory = KQuicTransportFactory { PendingTransport }
            ),
            KQuicConfig(
                initiator = true,
                rendezvousId = "room-1",
                security = security,
                gatherHostCandidates = false
            )
        )
        peer.start()
        assertFailsWith<KQuicError.InvalidSignal> {
            peer.signal(
                KQuicAnswer(
                    "room-1",
                    listOf(candidate("relay", KQuicCandidateType.RELAY, 1)),
                    TEST_FINGERPRINT,
                    TEST_CREDENTIALS
                )
            )
        }
        peer.close()
    }

    @Test
    fun signal_from_other_rendezvous_is_rejected() = runBlocking {
        val peer = KQuicPeer(
            KQuicContext(this),
            KQuicConfig(
                initiator = false,
                rendezvousId = "room-1",
                security = security,
                gatherHostCandidates = false
            )
        )
        assertFailsWith<KQuicError.InvalidSignal> {
            peer.signal(KQuicOffer("room-2", emptyList(), TEST_FINGERPRINT, TEST_CREDENTIALS))
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

    private fun testIdentity() = KQuicIdentity.platformGenerated(
        certificateChainDer = listOf(byteArrayOf(1)),
        signer = KQuicSigner { _, _ -> byteArrayOf(1) },
        publicKeySha256 = TEST_FINGERPRINT
    )

    private object PendingTransport : KQuicTransport {
        override val events: Flow<KQuicTransportEvent> = emptyFlow()
        override suspend fun gatherCandidates(): List<KQuicCandidate> =
            listOf(
                KQuicCandidate(
                    foundation = "local",
                    type = KQuicCandidateType.HOST,
                    host = "127.0.0.1",
                    port = 5000,
                    priority = 10
                )
            )

        override suspend fun connect(
            candidatePair: KQuicCandidatePair,
            identity: KQuicIdentity,
            role: KQuicTransportRole,
            localCredentials: KQuicConnectivityCredentials,
            remoteCredentials: KQuicConnectivityCredentials
        ): KQuicRemoteIdentity = awaitCancellation()

        override fun openChannel(config: KQuicChannelConfig) = Unit
        override fun send(label: String, payload: ByteArray, text: Boolean): Boolean = false
        override fun closeChannel(label: String) = Unit
        override fun close() = Unit
    }

    private companion object {
        const val TEST_FINGERPRINT =
            "0000000000000000000000000000000000000000000000000000000000000000"
        val TEST_CREDENTIALS = KQuicConnectivityCredentials("local-user", "local-password-123")
    }
}
