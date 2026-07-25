package com.kerobit.kquic

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalKQuicApi::class)
class KQuicPeerTransportTest {
    @Test
    fun two_peers_open_a_channel_and_exchange_binary_and_text_messages() = runBlocking {
        val initiatorTransport = TestTransport()
        val responderTransport = TestTransport()
        initiatorTransport.remote = responderTransport
        responderTransport.remote = initiatorTransport

        val initiator = peer(initiator = true, initiatorTransport)
        val responder = peer(initiator = false, responderTransport)
        val local = candidate("local", 10)
        val remote = candidate("remote", 9)

        responder.signal(KQuicOffer("room", listOf(remote), TEST_FINGERPRINT))
        initiator.signal(KQuicAnswer("room", listOf(remote), TEST_FINGERPRINT))
        awaitConnected(initiator)
        awaitConnected(responder)
        assertEquals(KQuicTransportRole.CLIENT, initiatorTransport.lastRole)
        assertEquals(KQuicTransportRole.SERVER, responderTransport.lastRole)

        val incomingChannel = async(start = CoroutineStart.UNDISPATCHED) {
            responder.channels.first()
        }
        val outbound = initiator.createChannel(KQuicChannelConfig("game"))
        val inbound = withTimeout(1_000) { incomingChannel.await() }
        assertEquals("game", inbound.label)
        assertEquals(KQuicChannelState.OPEN, inbound.currentState)

        val receivedBytes = async(start = CoroutineStart.UNDISPATCHED) { inbound.bytes.first() }
        assertTrue(outbound.send(byteArrayOf(1, 2, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(1_000) { receivedBytes.await() })

        val receivedText = async(start = CoroutineStart.UNDISPATCHED) { inbound.text.first() }
        assertTrue(outbound.send("hello"))
        assertEquals("hello", withTimeout(1_000) { receivedText.await() })

        outbound.close()
        withTimeout(1_000) {
            inbound.state.first { it == KQuicChannelState.CLOSED }
        }

        initiator.close()
        responder.close()
    }

    @Test
    fun connection_timeout_fails_and_closes_the_transport() = runBlocking {
        val transport = TestTransport(connectForever = true)
        val peer = peer(initiator = true, transport, connectionTimeoutMs = 50)

        peer.signal(KQuicAnswer("room", listOf(candidate("remote", 9)), TEST_FINGERPRINT))

        withTimeout(1_000) {
            peer.connectionState.first { it == KQuicPeerConnectionState.FAILED }
        }
        assertTrue(transport.closed)
        assertTrue(peer.stats.first().closeReason.orEmpty().contains("50 ms"))
        peer.close()
    }

    @Test
    fun signaling_replay_keeps_offer_snapshot_and_end_marker_for_late_subscribers() = runBlocking {
        val peer = peer(initiator = true, TestTransport())
        peer.start()

        val emitted = withTimeout(1_000) { peer.signals.take(3).toList() }
        assertTrue(emitted[0] is KQuicOffer)
        assertTrue(emitted[1] is KQuicCandidateUpdate)
        assertTrue(emitted[2] is KQuicEndOfCandidates)
        peer.close()
    }

    @Test
    fun failed_candidate_pair_falls_through_to_the_next_ordered_pair() = runBlocking {
        val transport = PairRetryTransport()
        val peer = KQuicPeer(
            context = KQuicContext(
                scope = this,
                platformContext = null,
                transportFactory = KQuicTransportFactory { transport }
            ),
            config = KQuicConfig(
                initiator = true,
                rendezvousId = "room",
                security = KQuicSecurity.Explicit(
                    identityProvider = KQuicIdentityProvider { testIdentity() },
                    peerVerifier = KQuicPeerVerifier { KQuicVerificationResult.Accepted }
                ),
                gatherHostCandidates = false,
                initialCandidates = listOf(candidate("a", 10))
            )
        )
        peer.signal(
            KQuicAnswer(
                rendezvousId = "room",
                candidates = listOf(candidate("high", 100), candidate("low", 1)),
                identityPublicKeySha256 = TEST_FINGERPRINT,
                endOfCandidates = true
            )
        )
        awaitConnected(peer)
        assertEquals(listOf("high", "low"), transport.attemptedFoundations)
        peer.close()
    }

    private fun kotlinx.coroutines.CoroutineScope.peer(
        initiator: Boolean,
        transport: TestTransport,
        connectionTimeoutMs: Long? = 30_000
    ): KQuicPeer = KQuicPeer(
        context = KQuicContext(
            scope = this,
            platformContext = null,
            transportFactory = KQuicTransportFactory { transport }
        ),
        config = KQuicConfig(
            initiator = initiator,
            rendezvousId = "room",
            security = KQuicSecurity.Explicit(
                identityProvider = KQuicIdentityProvider { testIdentity() },
                peerVerifier = KQuicPeerVerifier { KQuicVerificationResult.Accepted }
            ),
            gatherHostCandidates = false,
            initialCandidates = listOf(candidate(if (initiator) "a" else "b", 10)),
            connectionTimeoutMs = connectionTimeoutMs
        )
    )

    private suspend fun awaitConnected(peer: KQuicPeer) {
        withTimeout(1_000) {
            peer.connectionState.first { it == KQuicPeerConnectionState.CONNECTED }
        }
    }

    private fun candidate(foundation: String, priority: Long) = KQuicCandidate(
        foundation = foundation,
        type = KQuicCandidateType.HOST,
        host = "127.0.0.1",
        port = if (foundation == "a") 5001 else 5002,
        priority = priority
    )

    private class TestTransport(
        private val connectForever: Boolean = false
    ) : KQuicTransport {
        private val mutableEvents = MutableSharedFlow<KQuicTransportEvent>(
            replay = 16,
            extraBufferCapacity = 16
        )
        override val events: Flow<KQuicTransportEvent> = mutableEvents
        lateinit var remote: TestTransport
        var closed = false
            private set
        var lastRole: KQuicTransportRole? = null
            private set

        override suspend fun connect(
            candidatePair: KQuicCandidatePair,
            identity: KQuicIdentity,
            role: KQuicTransportRole
        ): KQuicRemoteIdentity {
            lastRole = role
            if (connectForever) awaitCancellation()
            return KQuicRemoteIdentity(
                certificateChainDer = listOf(byteArrayOf(1)),
                publicKeySha256 = TEST_FINGERPRINT,
                serverName = "test-peer"
            )
        }

        override fun openChannel(config: KQuicChannelConfig) {
            check(remote.mutableEvents.tryEmit(KQuicTransportEvent.IncomingChannel(config)))
        }

        override fun send(label: String, payload: ByteArray, text: Boolean): Boolean {
            val event = if (text) {
                KQuicTransportEvent.TextReceived(label, payload.decodeToString())
            } else {
                KQuicTransportEvent.BytesReceived(label, payload.copyOf())
            }
            return remote.mutableEvents.tryEmit(event)
        }

        override fun closeChannel(label: String) {
            check(remote.mutableEvents.tryEmit(KQuicTransportEvent.ChannelClosed(label)))
        }

        override fun close() {
            closed = true
        }
    }

    private class PairRetryTransport : KQuicTransport {
        override val events: Flow<KQuicTransportEvent> = MutableSharedFlow()
        val attemptedFoundations = mutableListOf<String>()

        override suspend fun connect(
            candidatePair: KQuicCandidatePair,
            identity: KQuicIdentity,
            role: KQuicTransportRole
        ): KQuicRemoteIdentity {
            attemptedFoundations += candidatePair.remote.foundation
            if (attemptedFoundations.size == 1) {
                throw KQuicError.TransportFailure("first pair rejected")
            }
            return KQuicRemoteIdentity(
                certificateChainDer = listOf(byteArrayOf(1)),
                publicKeySha256 = TEST_FINGERPRINT
            )
        }

        override fun openChannel(config: KQuicChannelConfig) = Unit
        override fun send(label: String, payload: ByteArray, text: Boolean): Boolean = true
        override fun closeChannel(label: String) = Unit
        override fun close() = Unit
    }

    private fun testIdentity() = KQuicIdentity.platformGenerated(
        certificateChainDer = listOf(byteArrayOf(1)),
        signer = KQuicSigner { _, _ -> byteArrayOf(1) },
        publicKeySha256 = TEST_FINGERPRINT
    )

    private companion object {
        const val TEST_FINGERPRINT =
            "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
