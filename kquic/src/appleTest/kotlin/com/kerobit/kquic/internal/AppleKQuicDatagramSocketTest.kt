package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicHandshakeRequest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppleKQuicDatagramSocketTest {
    @Test
    fun two_posix_udp_sockets_exchange_a_loopback_datagram() = runBlocking {
        val first = AppleKQuicDatagramSocketFactory.bind(KQuicSocketAddress("127.0.0.1", 0))
        val second = AppleKQuicDatagramSocketFactory.bind(KQuicSocketAddress("127.0.0.1", 0))
        try {
            val payload = byteArrayOf(1, 2, 3, 4)
            first.send(second.localAddress, payload)
            val received = withTimeout(2_000) { second.receive() }
            assertContentEquals(payload, received.payload)
            assertEquals(first.localAddress.port, received.source.port)
        } finally {
            first.close()
            second.close()
        }
    }

    @Test
    fun security_framework_completes_mutual_tls_key_agreement_and_signatures() = runBlocking {
        val services = createKQuicPlatformRuntime()
        val client = CommonQuicTlsSession(
            QuicEndpointRole.CLIENT,
            services.crypto.ephemeralIdentityProvider.identityFor(
                KQuicHandshakeRequest("apple", true)
            ),
            parameters(byteArrayOf(1)),
            services.crypto.handshakeCrypto
        )
        val server = CommonQuicTlsSession(
            QuicEndpointRole.SERVER,
            services.crypto.ephemeralIdentityProvider.identityFor(
                KQuicHandshakeRequest("apple", false)
            ),
            parameters(byteArrayOf(2)),
            services.crypto.handshakeCrypto
        )
        val clientHello = client.start().single() as QuicTlsEvent.CryptoData
        val serverEvents = server.receive(QuicEncryptionLevel.INITIAL, clientHello.bytes)
        val serverHello = serverEvents.filterIsInstance<QuicTlsEvent.CryptoData>()
            .single { it.level == QuicEncryptionLevel.INITIAL }
        val serverFlight = serverEvents.filterIsInstance<QuicTlsEvent.CryptoData>()
            .single { it.level == QuicEncryptionLevel.HANDSHAKE }
        client.receive(QuicEncryptionLevel.INITIAL, serverHello.bytes)
        val clientEvents = client.receive(QuicEncryptionLevel.HANDSHAKE, serverFlight.bytes)
        val clientFlight = clientEvents.filterIsInstance<QuicTlsEvent.CryptoData>().single()
        val serverFinished = server.receive(QuicEncryptionLevel.HANDSHAKE, clientFlight.bytes)
        assertTrue(clientEvents.any { it is QuicTlsEvent.HandshakeComplete })
        assertTrue(serverFinished.any { it is QuicTlsEvent.HandshakeComplete })
    }

    private fun parameters(connectionId: ByteArray) = QuicTransportParameterValues(
        initialMaxData = 1_048_576uL,
        initialMaxStreamDataBidiLocal = 262_144uL,
        initialMaxStreamDataBidiRemote = 262_144uL,
        initialMaxStreamDataUni = 262_144uL,
        initialMaxStreamsBidi = 64uL,
        initialMaxStreamsUni = 64uL,
        initialSourceConnectionId = connectionId,
        maxDatagramFrameSize = 1_200uL
    )
}
