package com.kerobit.kquic

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val incomingChannel = async(start = CoroutineStart.UNDISPATCHED) {
            responder.channels.first()
        }
        val outbound = initiator.createChannel(KQuicChannelConfig("game"))
        assertEquals(KQuicChannelState.CONNECTING, outbound.currentState)
        assertEquals(0, initiatorTransport.openChannelCalls)

        initiator.start()
        responder.signal(KQuicOffer("room", listOf(remote), TEST_FINGERPRINT, TEST_CREDENTIALS))
        initiator.signal(KQuicAnswer("room", listOf(remote), TEST_FINGERPRINT, TEST_CREDENTIALS))
        awaitConnected(initiator)
        awaitConnected(responder)
        assertEquals(KQuicTransportRole.CLIENT, initiatorTransport.lastRole)
        assertEquals(KQuicTransportRole.SERVER, responderTransport.lastRole)
        assertEquals(1, responderTransport.gatherCalls)

        val inbound = withTimeout(1_000) { incomingChannel.await() }
        assertEquals(1, initiatorTransport.openChannelCalls)
        assertEquals(KQuicChannelState.OPEN, outbound.currentState)
        assertEquals("game", inbound.label)
        assertEquals(KQuicChannelState.OPEN, inbound.currentState)

        val receivedBytes = async(start = CoroutineStart.UNDISPATCHED) { inbound.bytes.first() }
        assertTrue(outbound.send(byteArrayOf(1, 2, 3)))
        assertContentEquals(byteArrayOf(1, 2, 3), withTimeout(1_000) { receivedBytes.await() })
        assertEquals(3, outbound.currentBufferedAmount)

        val receivedText = async(start = CoroutineStart.UNDISPATCHED) { inbound.text.first() }
        assertTrue(outbound.send("hello"))
        assertEquals("hello", withTimeout(1_000) { receivedText.await() })
        assertEquals(8, outbound.currentBufferedAmount)

        outbound.close()
        assertEquals(0, outbound.currentBufferedAmount)
        withTimeout(1_000) {
            inbound.state.first { it == KQuicChannelState.CLOSED }
        }

        initiator.close()
        responder.close()
    }

    @Test
    fun connection_timeout_fails_and_closes_the_transport() = runBlocking {
        val transport = TestTransport(connectForever = true)
        val logger = RecordingLogger()
        val peer = peer(
            initiator = true,
            transport = transport,
            connectionTimeoutMs = 50,
            logger = logger
        )

        peer.start()
        peer.signal(
            KQuicAnswer(
                "room",
                listOf(candidate("remote", 9)),
                TEST_FINGERPRINT,
                TEST_CREDENTIALS
            )
        )

        withTimeout(1_000) {
            peer.connectionState.first { it == KQuicPeerConnectionState.FAILED }
        }
        assertTrue(transport.closed)
        assertTrue(peer.getStats().closeReason.orEmpty().contains("50 ms"))
        assertTrue(logger.messages.any {
            it.contains("ConnectionTimedOut") &&
                it.contains("QUIC connection did not complete within 50 ms")
        })
        assertTrue(logger.messages.any { it.contains("Candidate pair failed") })
        assertTrue(logger.messages.none { it.contains(TEST_CREDENTIALS.password) })
        peer.close()
    }

    @Test
    fun signaling_replay_keeps_complete_offer_snapshot_for_late_subscribers() = runBlocking {
        val peer = peer(initiator = true, TestTransport())
        peer.start()

        val emitted = withTimeout(1_000) { peer.signals.take(1).toList() }
        val offer = emitted.single() as KQuicOffer
        assertTrue(offer.endOfCandidates)
        assertTrue(offer.candidates.isNotEmpty())
        peer.close()
    }

    @Test
    fun create_channel_before_start_performs_no_io() = runBlocking {
        val transport = TestTransport()
        val peer = peer(initiator = true, transport)

        val first = peer.createChannel(KQuicChannelConfig("prepared-1"))
        val second = peer.createChannel(KQuicChannelConfig("prepared-2"))

        assertEquals(KQuicChannelState.CONNECTING, first.currentState)
        assertEquals(KQuicChannelState.CONNECTING, second.currentState)
        assertEquals(KQuicPeerConnectionState.NEW, peer.currentConnectionState)
        assertEquals(0, transport.gatherCalls)
        assertEquals(0, transport.openChannelCalls)

        peer.close()
        assertEquals(KQuicChannelState.CLOSED, first.currentState)
        assertEquals(KQuicChannelState.CLOSED, second.currentState)
        assertFailsWith<KQuicError.InvalidChannel> {
            peer.createChannel(KQuicChannelConfig("after-close"))
        }
        Unit
    }

    @Test
    fun channel_created_during_handshake_opens_after_connection() = runBlocking {
        val connectGate = CompletableDeferred<Unit>()
        val transport = TestTransport(connectGate = connectGate)
        val peer = peer(initiator = true, transport)
        peer.start()
        peer.signal(
            KQuicAnswer(
                "room",
                listOf(candidate("remote", 9)),
                TEST_FINGERPRINT,
                TEST_CREDENTIALS
            )
        )
        withTimeout(1_000) {
            peer.connectionState.first { it == KQuicPeerConnectionState.CONNECTING }
        }

        val channel = peer.createChannel(KQuicChannelConfig("during-handshake"))
        assertEquals(KQuicChannelState.CONNECTING, channel.currentState)
        assertEquals(0, transport.openChannelCalls)

        connectGate.complete(Unit)
        awaitConnected(peer)
        assertEquals(KQuicChannelState.OPEN, channel.currentState)
        assertEquals(1, transport.openChannelCalls)

        val connectedChannel = peer.createChannel(KQuicChannelConfig("already-connected"))
        assertEquals(KQuicChannelState.OPEN, connectedChannel.currentState)
        assertEquals(2, transport.openChannelCalls)
        peer.close()
    }

    @Test
    fun concurrent_start_initializes_identity_and_gathering_once() = runBlocking {
        var identityCalls = 0
        var identityReleaseCalls = 0
        val transport = TestTransport()
        val peer = KQuicPeer(
            context = KQuicContext(
                scope = this,
                transportFactory = KQuicTransportFactory { transport }
            ),
            config = KQuicConfig(
                initiator = true,
                rendezvousId = "room",
                security = KQuicSecurity.Explicit(
                    identityProvider = KQuicIdentityProvider {
                        identityCalls++
                        KQuicIdentity.platformGenerated(
                            certificateChainDer = listOf(byteArrayOf(1)),
                            signer = KQuicSigner { _, _ -> byteArrayOf(1) },
                            publicKeySha256 = TEST_FINGERPRINT,
                            releasePlatformKey = { identityReleaseCalls++ }
                        )
                    },
                    peerVerifier = KQuicPeerVerifier { KQuicVerificationResult.Accepted }
                ),
                gatherHostCandidates = false
            )
        )

        coroutineScope {
            repeat(32) { launch { peer.start() } }
        }
        withTimeout(1_000) { peer.signals.first() }

        assertEquals(1, identityCalls)
        assertEquals(1, transport.gatherCalls)
        peer.close()
        assertEquals(1, identityReleaseCalls)
    }

    @Test
    fun close_during_handshake_closes_pending_channels() = runBlocking {
        val connectGate = CompletableDeferred<Unit>()
        val transport = TestTransport(connectGate = connectGate)
        val peer = peer(initiator = true, transport)
        val channel = peer.createChannel(KQuicChannelConfig("pending"))
        peer.start()
        peer.signal(
            KQuicAnswer(
                "room",
                listOf(candidate("remote", 9)),
                TEST_FINGERPRINT,
                TEST_CREDENTIALS
            )
        )
        withTimeout(1_000) {
            peer.connectionState.first { it == KQuicPeerConnectionState.CONNECTING }
        }

        peer.close()

        assertEquals(KQuicPeerConnectionState.CLOSED, peer.currentConnectionState)
        assertEquals(KQuicChannelState.CLOSED, channel.currentState)
        assertEquals(0, transport.openChannelCalls)
        assertTrue(transport.closed)
    }

    @Test
    fun failed_candidate_pair_falls_through_to_the_next_ordered_pair() = runBlocking {
        val transport = PairRetryTransport()
        val peer = KQuicPeer(
            context = KQuicContext(
                scope = this,
                transportFactory = KQuicTransportFactory { transport }
            ),
            config = KQuicConfig(
                initiator = true,
                rendezvousId = "room",
                security = KQuicSecurity.Explicit(
                    identityProvider = KQuicIdentityProvider { testIdentity() },
                    peerVerifier = KQuicPeerVerifier { KQuicVerificationResult.Accepted }
                ),
                gatherHostCandidates = false
            )
        )
        peer.start()
        peer.signal(
            KQuicAnswer(
                rendezvousId = "room",
                candidates = listOf(candidate("high", 100), candidate("low", 1)),
                identityPublicKeySha256 = TEST_FINGERPRINT,
                connectivityCredentials = TEST_CREDENTIALS,
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
        connectionTimeoutMs: Long? = 30_000,
        logger: KQuicLogger = NoOpKQuicLogger
    ): KQuicPeer = KQuicPeer(
        context = KQuicContext(
            scope = this,
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
            connectionTimeoutMs = connectionTimeoutMs
        ),
        logger = logger
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
        private val connectForever: Boolean = false,
        private val connectGate: CompletableDeferred<Unit>? = null
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
        var gatherCalls = 0
            private set
        var openChannelCalls = 0
            private set

        override suspend fun gatherCandidates(): List<KQuicCandidate> {
            gatherCalls++
            return listOf(candidateForTransport("local", 10))
        }

        override suspend fun connect(
            candidatePair: KQuicCandidatePair,
            identity: KQuicIdentity,
            role: KQuicTransportRole,
            localCredentials: KQuicConnectivityCredentials,
            remoteCredentials: KQuicConnectivityCredentials
        ): KQuicRemoteIdentity {
            lastRole = role
            if (connectForever) awaitCancellation()
            connectGate?.await()
            return KQuicRemoteIdentity(
                certificateChainDer = listOf(byteArrayOf(1)),
                publicKeySha256 = TEST_FINGERPRINT,
                serverName = "test-peer"
            )
        }

        override fun openChannel(config: KQuicChannelConfig) {
            openChannelCalls++
            if (::remote.isInitialized) {
                check(remote.mutableEvents.tryEmit(KQuicTransportEvent.IncomingChannel(config)))
            }
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
            if (::remote.isInitialized) {
                check(remote.mutableEvents.tryEmit(KQuicTransportEvent.ChannelClosed(label)))
            }
        }

        override fun close() {
            closed = true
        }
    }

    private class PairRetryTransport : KQuicTransport {
        override val events: Flow<KQuicTransportEvent> = MutableSharedFlow()
        val attemptedFoundations = mutableListOf<String>()
        override suspend fun gatherCandidates(): List<KQuicCandidate> =
            listOf(candidateForTransport("a", 10))

        override suspend fun connect(
            candidatePair: KQuicCandidatePair,
            identity: KQuicIdentity,
            role: KQuicTransportRole,
            localCredentials: KQuicConnectivityCredentials,
            remoteCredentials: KQuicConnectivityCredentials
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

    private class RecordingLogger : KQuicLogger {
        val messages = mutableListOf<String>()

        override fun debug(message: String) {
            messages += message
        }

        override fun info(message: String) {
            messages += message
        }

        override fun warn(message: String) {
            messages += message
        }

        override fun error(message: String) {
            messages += message
        }
    }

    private fun testIdentity() = KQuicIdentity.platformGenerated(
        certificateChainDer = listOf(byteArrayOf(1)),
        signer = KQuicSigner { _, _ -> byteArrayOf(1) },
        publicKeySha256 = TEST_FINGERPRINT
    )

    private companion object {
        const val TEST_FINGERPRINT =
            "0000000000000000000000000000000000000000000000000000000000000000"
        val TEST_CREDENTIALS = KQuicConnectivityCredentials("remote-user", "remote-password-123")

        fun candidateForTransport(foundation: String, priority: Long) = KQuicCandidate(
            foundation = foundation,
            type = KQuicCandidateType.HOST,
            host = "127.0.0.1",
            port = 5001,
            priority = priority
        )
    }
}
