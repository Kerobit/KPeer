package com.kerobit.kquic.internal

import com.kerobit.kquic.ExperimentalKQuicApi
import com.kerobit.kquic.KQuicAnswer
import com.kerobit.kquic.KQuicChannelConfig
import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicConnectivityCredentials
import com.kerobit.kquic.KQuicContext
import com.kerobit.kquic.KQuicDelivery
import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicOffer
import com.kerobit.kquic.KQuicPeer
import com.kerobit.kquic.KQuicPeerConnectionState
import com.kerobit.kquic.KQuicSecurity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalKQuicApi::class)
class CommonKQuicLoopbackTest {
    @Test
    fun real_udp_transport_connects_and_exchanges_reliable_bytes() = runBlocking {
        val initiator = peer(true)
        val responder = peer(false)
        val initiatorStateTrace = mutableListOf<KQuicPeerConnectionState>()
        val responderStateTrace = mutableListOf<KQuicPeerConnectionState>()
        val initiatorStateSubscription = initiator.onConnectionState(initiatorStateTrace::add)
        val responderStateSubscription = responder.onConnectionState(responderStateTrace::add)
        try {
            val offer = async(start = CoroutineStart.UNDISPATCHED) {
                initiator.signals.filterIsInstance<KQuicOffer>().first()
            }
            initiator.start()
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                responder.signals.filterIsInstance<KQuicAnswer>().first()
            }
            responder.signal(withTimeout(5_000) { offer.await() })
            initiator.signal(withTimeout(5_000) { answer.await() })

            val initiatorConnected = withTimeoutOrNull(15_000) {
                initiator.connectionState.first { it == KQuicPeerConnectionState.CONNECTED }
            }
            check(initiatorConnected != null) {
                "Initiator states=$initiatorStateTrace, responder states=$responderStateTrace, " +
                    "initiator stats=${initiator.stats.first()}, responder stats=${responder.stats.first()}"
            }
            withTimeout(10_000) {
                responder.connectionState.first { it == KQuicPeerConnectionState.CONNECTED }
            }

            val incomingChannel = async(start = CoroutineStart.UNDISPATCHED) {
                responder.channels.first()
            }
            val outbound = initiator.createChannel(KQuicChannelConfig("reliable"))
            val inbound = withTimeout(5_000) { incomingChannel.await() }
            val received = async(start = CoroutineStart.UNDISPATCHED) { inbound.bytes.first() }
            assertTrue(outbound.send(byteArrayOf(1, 2, 3, 4)))
            assertContentEquals(
                byteArrayOf(1, 2, 3, 4),
                withTimeout(5_000) { received.await() }
            )

            val fragmentedIncomingChannel = async(start = CoroutineStart.UNDISPATCHED) {
                responder.channels.first { it.label == "fragmented" }
            }
            val fragmentedOutbound = initiator.createChannel(
                KQuicChannelConfig("fragmented")
            )
            val fragmentedInbound = withTimeout(5_000) { fragmentedIncomingChannel.await() }
            val fragmentedPayload = ByteArray(8_192) { (it and 0xff).toByte() }
            val fragmentedReceived = async(start = CoroutineStart.UNDISPATCHED) {
                fragmentedInbound.bytes.first()
            }
            assertTrue(fragmentedOutbound.send(fragmentedPayload))
            assertContentEquals(
                fragmentedPayload,
                withTimeout(10_000) { fragmentedReceived.await() }
            )

            val datagramIncomingChannel = async(start = CoroutineStart.UNDISPATCHED) {
                responder.channels.first { it.label == "datagram" }
            }
            val datagramOutbound = initiator.createChannel(
                KQuicChannelConfig("datagram", KQuicDelivery.UNRELIABLE)
            )
            val datagramInbound = withTimeout(5_000) { datagramIncomingChannel.await() }
            val datagramReceived = async(start = CoroutineStart.UNDISPATCHED) {
                datagramInbound.text.first()
            }
            assertTrue(datagramOutbound.send("hello over quic datagram"))
            assertFailsWith<KQuicError.MessageTooLarge> {
                datagramOutbound.send(ByteArray(1_101))
            }
            assertContentEquals(
                "hello over quic datagram".encodeToByteArray(),
                withTimeout(5_000) { datagramReceived.await() }.encodeToByteArray()
            )

            val remoteClosed = async(start = CoroutineStart.UNDISPATCHED) {
                responder.connectionState.first {
                    it == KQuicPeerConnectionState.DISCONNECTED
                }
            }
            initiator.close()
            assertEquals(
                KQuicPeerConnectionState.DISCONNECTED,
                withTimeout(5_000) { remoteClosed.await() }
            )
            assertEquals("application_close", responder.stats.first().closeReason)
        } finally {
            initiatorStateSubscription.cancel()
            responderStateSubscription.cancel()
            initiator.close()
            responder.close()
        }
    }

    @Test
    fun connectivity_check_uses_the_peer_reflexive_endpoint_after_nat_translation() = runBlocking {
        val network = AsymmetricNatDatagramNetwork()
        val initiatorEndpoint = NatEndpoint(
            privateAddress = KQuicSocketAddress("10.0.2.16", 41_000),
            translatedAddress = KQuicSocketAddress("192.168.1.200", 51_000),
            privateAddressReachable = false,
        )
        val responderEndpoint = NatEndpoint(
            privateAddress = KQuicSocketAddress("192.168.1.139", 42_000),
            translatedAddress = KQuicSocketAddress("192.168.1.139", 42_000),
            privateAddressReachable = true,
        )
        val initiator = natPeer(true, initiatorEndpoint, network)
        val responder = natPeer(false, responderEndpoint, network)
        try {
            val offer = async(start = CoroutineStart.UNDISPATCHED) {
                initiator.signals.filterIsInstance<KQuicOffer>().first()
            }
            initiator.start()
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                responder.signals.filterIsInstance<KQuicAnswer>().first()
            }
            responder.signal(withTimeout(5_000) { offer.await() })
            initiator.signal(withTimeout(5_000) { answer.await() })

            withTimeout(10_000) {
                initiator.connectionState.first { it == KQuicPeerConnectionState.CONNECTED }
            }
            withTimeout(10_000) {
                responder.connectionState.first { it == KQuicPeerConnectionState.CONNECTED }
            }

            val incomingChannel = async(start = CoroutineStart.UNDISPATCHED) {
                responder.channels.first()
            }
            val outbound = initiator.createChannel(KQuicChannelConfig("nat-reliable"))
            val inbound = withTimeout(5_000) { incomingChannel.await() }
            val received = async(start = CoroutineStart.UNDISPATCHED) {
                inbound.bytes.first()
            }
            assertTrue(outbound.send(byteArrayOf(9, 8, 7)))
            assertContentEquals(
                byteArrayOf(9, 8, 7),
                withTimeout(5_000) { received.await() },
            )
            assertTrue(network.droppedPacketsToUnreachablePrivateAddress > 0)
        } finally {
            initiator.close()
            responder.close()
        }
    }

    @Test
    fun authenticated_signaling_rejects_a_tampered_identity_fingerprint() = runBlocking {
        val initiator = peer(true)
        val responder = peer(false)
        try {
            val offer = async(start = CoroutineStart.UNDISPATCHED) {
                initiator.signals.filterIsInstance<KQuicOffer>().first()
            }
            initiator.start()
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                responder.signals.filterIsInstance<KQuicAnswer>().first()
            }
            responder.signal(withTimeout(5_000) { offer.await() })
            val genuineAnswer = withTimeout(5_000) { answer.await() }
            initiator.signal(
                genuineAnswer.copy(identityPublicKeySha256 = "00".repeat(32))
            )

            assertEquals(
                KQuicPeerConnectionState.FAILED,
                withTimeout(10_000) {
                    initiator.connectionState.first {
                        it == KQuicPeerConnectionState.FAILED
                    }
                }
            )
            assertTrue(
                initiator.stats.first().closeReason.orEmpty().contains(
                    "fingerprint",
                    ignoreCase = true
                )
            )
        } finally {
            initiator.close()
            responder.close()
        }
    }

    @Test
    fun connectivity_checks_reject_tampered_credentials_and_time_out() = runBlocking {
        val initiator = peer(true, connectivityTimeoutMs = 1_000)
        val responder = peer(false, connectivityTimeoutMs = 1_000)
        try {
            val offer = async(start = CoroutineStart.UNDISPATCHED) {
                initiator.signals.filterIsInstance<KQuicOffer>().first()
            }
            initiator.start()
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                responder.signals.filterIsInstance<KQuicAnswer>().first()
            }
            responder.signal(withTimeout(5_000) { offer.await() })
            val genuineAnswer = withTimeout(5_000) { answer.await() }
            initiator.signal(
                genuineAnswer.copy(
                    connectivityCredentials = KQuicConnectivityCredentials(
                        genuineAnswer.connectivityCredentials.usernameFragment,
                        "incorrect-password"
                    )
                )
            )

            withTimeout(5_000) {
                initiator.connectionState.first { it == KQuicPeerConnectionState.FAILED }
            }
            assertTrue(
                initiator.stats.first().closeReason.orEmpty().contains(
                    "connectivity checks",
                    ignoreCase = true
                )
            )
        } finally {
            initiator.close()
            responder.close()
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.peer(
        initiator: Boolean,
        connectivityTimeoutMs: Long = 2_000
    ) = KQuicPeer(
        context = KQuicContext(scope = this),
        config = KQuicConfig(
            initiator = initiator,
            rendezvousId = "loopback-test",
            security = KQuicSecurity.ephemeralWithAuthenticatedSignaling(),
            connectionTimeoutMs = 20_000,
            connectivityCheckTimeoutMs = connectivityTimeoutMs
        )
    )

    private fun kotlinx.coroutines.CoroutineScope.natPeer(
        initiator: Boolean,
        endpoint: NatEndpoint,
        network: AsymmetricNatDatagramNetwork,
    ): KQuicPeer {
        val platformServices = NatKQuicPlatformServices(
            delegate = createKQuicPlatformServices(null),
            datagramSocketFactory = network.factory(endpoint),
            hostAddress = endpoint.privateAddress.host,
        )
        return KQuicPeer(
            context = KQuicContext(
                scope = this,
                platformContext = null,
                transportFactory = CommonKQuicTransportFactory(this, platformServices),
                platformServices = platformServices,
            ),
            config = KQuicConfig(
                initiator = initiator,
                rendezvousId = "nat-loopback-test",
                security = KQuicSecurity.ephemeralWithAuthenticatedSignaling(),
                connectionTimeoutMs = 10_000,
                connectivityCheckTimeoutMs = 2_000,
            ),
        )
    }
}

private data class NatEndpoint(
    val privateAddress: KQuicSocketAddress,
    val translatedAddress: KQuicSocketAddress,
    val privateAddressReachable: Boolean,
)

private class NatKQuicPlatformServices(
    delegate: KQuicPlatformServices,
    override val datagramSocketFactory: KQuicDatagramSocketFactory,
    private val hostAddress: String,
) : KQuicPlatformServices by delegate {
    override fun hostAddresses(): List<String> = listOf(hostAddress)
}

private class AsymmetricNatDatagramNetwork {
    private val mutex = Mutex()
    private val routes = mutableMapOf<KQuicSocketAddress, Channel<KQuicInboundDatagram>>()
    var droppedPacketsToUnreachablePrivateAddress: Int = 0
        private set

    fun factory(endpoint: NatEndpoint): KQuicDatagramSocketFactory =
        KQuicDatagramSocketFactory { requestedAddress ->
            require(requestedAddress.host == endpoint.privateAddress.host)
            require(requestedAddress.port == 0)
            val inbound = Channel<KQuicInboundDatagram>(Channel.UNLIMITED)
            mutex.withLock {
                check(routes.put(endpoint.translatedAddress, inbound) == null)
                if (endpoint.privateAddressReachable &&
                    endpoint.privateAddress != endpoint.translatedAddress
                ) {
                    check(routes.put(endpoint.privateAddress, inbound) == null)
                }
            }
            object : KQuicDatagramSocket {
                override val localAddress: KQuicSocketAddress = endpoint.privateAddress

                override suspend fun send(
                    destination: KQuicSocketAddress,
                    payload: ByteArray,
                ) {
                    val target = mutex.withLock { routes[destination] }
                    if (target == null) {
                        mutex.withLock {
                            droppedPacketsToUnreachablePrivateAddress++
                        }
                        return
                    }
                    target.send(
                        KQuicInboundDatagram(
                            source = endpoint.translatedAddress,
                            payload = payload.copyOf(),
                        )
                    )
                }

                override suspend fun receive(): KQuicInboundDatagram = inbound.receive()

                override fun close() {
                    inbound.close()
                }
            }
        }
}
