package com.kerobit.kquic.internal

import com.kerobit.kquic.ExperimentalKQuicApi
import com.kerobit.kquic.KQuicChannelConfig
import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicHandshakeRequest
import com.kerobit.kquic.KQuicSecurity
import com.kerobit.kquic.KQuicTransportEvent
import com.kerobit.kquic.KQuicTransportRole
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalKQuicApi::class)
class CommonQuicConnectionActorNetworkTest {
    @Test
    fun actor_recovers_from_loss_duplication_and_reordering_at_1200_byte_mtu() = runBlocking {
        val services = createKQuicPlatformRuntime()
        val network = DeterministicDatagramNetwork()
        val clientSocket = network.socket(41_001)
        val serverSocket = network.socket(41_002)
        val clientEvents = Channel<KQuicTransportEvent>(Channel.UNLIMITED)
        val serverEvents = Channel<KQuicTransportEvent>(Channel.UNLIMITED)
        val config = KQuicConfig(
            initiator = true,
            rendezvousId = "deterministic-network",
            security = KQuicSecurity.ephemeralWithAuthenticatedSignaling(),
            connectionTimeoutMs = 10_000
        )
        val clientIdentity = services.crypto.ephemeralIdentityProvider.identityFor(
            KQuicHandshakeRequest(config.rendezvousId, true)
        )
        val serverIdentity = services.crypto.ephemeralIdentityProvider.identityFor(
            KQuicHandshakeRequest(config.rendezvousId, false)
        )
        val client = CommonQuicConnectionActor(
            parentScope = this,
            socket = clientSocket,
            remoteAddress = serverSocket.localAddress,
            role = KQuicTransportRole.CLIENT,
            identity = clientIdentity,
            config = config,
            platformRuntime = services,
            emitEvent = clientEvents::send
        )
        val server = CommonQuicConnectionActor(
            parentScope = this,
            socket = serverSocket,
            remoteAddress = clientSocket.localAddress,
            role = KQuicTransportRole.SERVER,
            identity = serverIdentity,
            config = config.copy(initiator = false),
            platformRuntime = services,
            emitEvent = serverEvents::send
        )
        try {
            val serverConnected = async(start = CoroutineStart.UNDISPATCHED) { server.connect() }
            val clientConnected = async(start = CoroutineStart.UNDISPATCHED) { client.connect() }
            withTimeout(10_000) {
                clientConnected.await()
                serverConnected.await()
            }

            val payloads = List(6) { message ->
                ByteArray(70_000) { (it * 31 + message).toByte() }
            }
            assertTrue(client.openChannel(KQuicChannelConfig("lossy-reliable")))
            payloads.forEach {
                assertTrue(client.send("lossy-reliable", it, text = false))
            }
            val saturationResults = List(20) {
                client.send("lossy-reliable", ByteArray(70_000), text = false)
            }
            assertTrue(saturationResults.any { !it }, "send() must expose local queue backpressure")

            val received = mutableListOf<ByteArray>()
            withTimeout(30_000) {
                while (received.size < payloads.size) {
                    when (val event = serverEvents.receive()) {
                        is KQuicTransportEvent.BytesReceived -> {
                            if (event.label == "lossy-reliable") received += event.payload
                        }
                        is KQuicTransportEvent.Failed -> throw event.error
                        else -> Unit
                    }
                }
            }
            payloads.zip(received).forEach { (expected, actual) ->
                assertContentEquals(expected, actual)
            }
            assertTrue(network.droppedPackets > 0)
            assertTrue(network.duplicatedPackets > 0)
            assertTrue(network.reorderedPackets > 0)
            assertTrue(network.maximumPayloadSize <= 1_200)
        } finally {
            client.close()
            server.close()
        }
    }
}

private class DeterministicDatagramNetwork {
    private val mutex = Mutex()
    private val inbound = mutableMapOf<Int, Channel<KQuicInboundDatagram>>()
    private var sequence = 0
    private var held: Pair<Int, KQuicInboundDatagram>? = null
    var droppedPackets = 0
        private set
    var duplicatedPackets = 0
        private set
    var reorderedPackets = 0
        private set
    var maximumPayloadSize = 0
        private set

    fun socket(port: Int): KQuicDatagramSocket {
        val channel = Channel<KQuicInboundDatagram>(Channel.UNLIMITED)
        inbound[port] = channel
        return object : KQuicDatagramSocket {
            override val localAddress = KQuicSocketAddress("127.0.0.1", port)

            override suspend fun send(destination: KQuicSocketAddress, payload: ByteArray) {
                mutex.withLock {
                    maximumPayloadSize = maxOf(maximumPayloadSize, payload.size)
                    sequence++
                    val datagram = KQuicInboundDatagram(localAddress, payload.copyOf())
                    if (sequence == 1) {
                        droppedPackets++
                        return@withLock
                    }
                    if (sequence == 4) {
                        held = destination.port to datagram
                        return@withLock
                    }
                    checkNotNull(inbound[destination.port]).send(datagram)
                    if (sequence == 5) {
                        val pending = checkNotNull(held)
                        checkNotNull(inbound[pending.first]).send(pending.second)
                        held = null
                        reorderedPackets++
                    }
                    if (sequence == 6) {
                        checkNotNull(inbound[destination.port]).send(datagram.copy(
                            payload = datagram.payload.copyOf()
                        ))
                        duplicatedPackets++
                    }
                }
            }

            override suspend fun receive(): KQuicInboundDatagram = channel.receive()

            override fun close() {
                channel.close()
            }
        }
    }
}
