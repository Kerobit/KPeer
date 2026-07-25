package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicIdentity
import com.kerobit.kquic.KQuicHandshakeRequest
import com.kerobit.kquic.KQuicSigner
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CommonQuicTlsSessionTest {
    @Test
    fun client_and_server_complete_mutual_tls_with_fragmented_crypto() = runBlocking {
        val clientCrypto = FakeHandshakeCrypto(1)
        val serverCrypto = FakeHandshakeCrypto(2)
        val client = CommonQuicTlsSession(
            QuicEndpointRole.CLIENT,
            identity(clientCrypto),
            parameters(byteArrayOf(1)),
            clientCrypto
        )
        val server = CommonQuicTlsSession(
            QuicEndpointRole.SERVER,
            identity(serverCrypto),
            parameters(byteArrayOf(2)),
            serverCrypto
        )

        val clientHello = client.start().single() as QuicTlsEvent.CryptoData
        val serverEvents = server.receive(QuicEncryptionLevel.INITIAL, clientHello.bytes)
        val serverHello = serverEvents.filterIsInstance<QuicTlsEvent.CryptoData>()
            .single { it.level == QuicEncryptionLevel.INITIAL }
        val serverFlight = serverEvents.filterIsInstance<QuicTlsEvent.CryptoData>()
            .single { it.level == QuicEncryptionLevel.HANDSHAKE }
        assertTrue(serverEvents.any { it is QuicTlsEvent.SecretsAvailable })
        client.receive(QuicEncryptionLevel.INITIAL, serverHello.bytes)

        val split = serverFlight.bytes.size / 2
        assertTrue(client.receive(
            QuicEncryptionLevel.HANDSHAKE,
            serverFlight.bytes.copyOfRange(0, split)
        ).none { it is QuicTlsEvent.HandshakeComplete })
        val clientEvents = client.receive(
            QuicEncryptionLevel.HANDSHAKE,
            serverFlight.bytes.copyOfRange(split, serverFlight.bytes.size)
        )
        assertTrue(clientEvents.any { it is QuicTlsEvent.HandshakeComplete })
        assertTrue(clientEvents.any { it is QuicTlsEvent.RemoteIdentityAvailable })
        val clientFlight = clientEvents.filterIsInstance<QuicTlsEvent.CryptoData>().single()

        val finalServerEvents = server.receive(
            QuicEncryptionLevel.HANDSHAKE,
            clientFlight.bytes
        )
        assertTrue(finalServerEvents.any { it is QuicTlsEvent.HandshakeComplete })
        assertTrue(finalServerEvents.any { it is QuicTlsEvent.RemoteIdentityAvailable })
        assertEquals(
            1,
            finalServerEvents.count { it is QuicTlsEvent.RemoteIdentityAvailable }
        )
    }

    @Test
    fun jca_p256_ecdhe_and_certificate_verify_complete_mutual_tls() = runBlocking {
        val services = createKQuicPlatformServices(null)
        val client = CommonQuicTlsSession(
            QuicEndpointRole.CLIENT,
            services.ephemeralIdentityProvider.identityFor(
                KQuicHandshakeRequest("jca", true)
            ),
            parameters(byteArrayOf(3)),
            JvmTls13HandshakeCrypto
        )
        val server = CommonQuicTlsSession(
            QuicEndpointRole.SERVER,
            services.ephemeralIdentityProvider.identityFor(
                KQuicHandshakeRequest("jca", false)
            ),
            parameters(byteArrayOf(4)),
            JvmTls13HandshakeCrypto
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
        val completed = server.receive(QuicEncryptionLevel.HANDSHAKE, clientFlight.bytes)

        assertTrue(clientEvents.any { it is QuicTlsEvent.HandshakeComplete })
        assertTrue(completed.any { it is QuicTlsEvent.HandshakeComplete })
    }

    @Test
    fun certificate_verify_corruption_aborts_the_handshake() = runBlocking {
        val clientCrypto = FakeHandshakeCrypto(1)
        val serverCrypto = FakeHandshakeCrypto(2)
        val client = CommonQuicTlsSession(
            QuicEndpointRole.CLIENT,
            identity(clientCrypto),
            parameters(byteArrayOf(1)),
            clientCrypto
        )
        val server = CommonQuicTlsSession(
            QuicEndpointRole.SERVER,
            identity(serverCrypto),
            parameters(byteArrayOf(2)),
            serverCrypto
        )
        val clientHello = client.start().single() as QuicTlsEvent.CryptoData
        val serverEvents = server.receive(QuicEncryptionLevel.INITIAL, clientHello.bytes)
        val serverHello = serverEvents.filterIsInstance<QuicTlsEvent.CryptoData>()
            .single { it.level == QuicEncryptionLevel.INITIAL }
        val serverFlight = serverEvents.filterIsInstance<QuicTlsEvent.CryptoData>()
            .single { it.level == QuicEncryptionLevel.HANDSHAKE }
        client.receive(QuicEncryptionLevel.INITIAL, serverHello.bytes)

        val corrupted = TlsHandshakeFramer().feed(serverFlight.bytes)
            .fold(ByteArray(0)) { encoded, message ->
                val changed = if (message.type == TlsHandshakeType.CERTIFICATE_VERIFY) {
                    message.copy(body = message.body.copyOf().also {
                        it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
                    })
                } else {
                    message
                }
                encoded + changed.encode()
            }
        assertFailsWith<IllegalStateException> {
            client.receive(QuicEncryptionLevel.HANDSHAKE, corrupted)
        }
        Unit
    }

    private suspend fun identity(crypto: FakeHandshakeCrypto): KQuicIdentity {
        val signer = KQuicSigner { _, message -> crypto.primitives.sha256(message) }
        return createEphemeralKQuicIdentity(
            subjectPublicKeyInfoDer = p256SubjectPublicKeyInfo(
                ByteArray(65) { index -> if (index == 0) 0x04 else crypto.marker.toByte() }
            ),
            randomSerial = ByteArray(16) { crypto.marker.toByte() },
            sha256 = crypto.primitives::sha256,
            signer = signer
        )
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

    private class FakeHandshakeCrypto(
        val marker: Int
    ) : Tls13HandshakeCrypto {
        override val primitives: QuicCryptoPrimitives = JvmQuicCryptoPrimitives

        override fun createP256KeyAgreement(): Tls13KeyAgreement = object : Tls13KeyAgreement {
            override val publicKey = ByteArray(65) { index ->
                if (index == 0) 0x04 else marker.toByte()
            }

            override fun deriveSharedSecret(peerPublicKey: ByteArray): ByteArray {
                require(peerPublicKey.size == 65)
                return ByteArray(32) { 0x55 }
            }
        }

        override fun secureRandom(size: Int): ByteArray = ByteArray(size) { marker.toByte() }

        override fun verifyEcdsaP256Sha256(
            subjectPublicKeyInfoDer: ByteArray,
            message: ByteArray,
            signature: ByteArray
        ): Boolean = primitives.sha256(message).contentEquals(signature)
    }
}
