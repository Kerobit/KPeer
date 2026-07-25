package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicRemoteIdentity

internal enum class QuicEncryptionLevel {
    INITIAL,
    HANDSHAKE,
    ONE_RTT
}

internal data class QuicTrafficSecrets(
    val read: ByteArray,
    val write: ByteArray
)

/**
 * Events emitted by the TLS protocol machine to the QUIC connection actor.
 *
 * TLS never writes packets or owns timers. It emits CRYPTO bytes and key transitions; the QUIC
 * actor decides how they are packetized, acknowledged and retransmitted.
 */
internal sealed interface QuicTlsEvent {
    data class CryptoData(val level: QuicEncryptionLevel, val bytes: ByteArray) : QuicTlsEvent
    data class SecretsAvailable(
        val level: QuicEncryptionLevel,
        val secrets: QuicTrafficSecrets
    ) : QuicTlsEvent
    data class RemoteIdentityAvailable(val identity: KQuicRemoteIdentity) : QuicTlsEvent
    data class TransportParametersAvailable(
        val parameters: QuicTransportParameterValues
    ) : QuicTlsEvent
    data object HandshakeComplete : QuicTlsEvent
    data class Alert(val description: UByte, val reason: String) : QuicTlsEvent
}

internal interface QuicTlsSession {
    suspend fun start(): List<QuicTlsEvent>
    suspend fun receive(
        level: QuicEncryptionLevel,
        cryptoBytes: ByteArray
    ): List<QuicTlsEvent>
}

internal interface Tls13KeyAgreement {
    val publicKey: ByteArray
    fun deriveSharedSecret(peerPublicKey: ByteArray): ByteArray
}

internal interface Tls13HandshakeCrypto {
    val primitives: Tls13HashPrimitives
    fun createP256KeyAgreement(): Tls13KeyAgreement
    fun secureRandom(size: Int): ByteArray
    fun verifyEcdsaP256Sha256(
        subjectPublicKeyInfoDer: ByteArray,
        message: ByteArray,
        signature: ByteArray
    ): Boolean
}

internal enum class TlsHandshakeType(val wireValue: Int) {
    CLIENT_HELLO(1),
    SERVER_HELLO(2),
    ENCRYPTED_EXTENSIONS(8),
    CERTIFICATE(11),
    CERTIFICATE_REQUEST(13),
    CERTIFICATE_VERIFY(15),
    FINISHED(20);

    companion object {
        fun fromWire(value: Int): TlsHandshakeType =
            entries.firstOrNull { it.wireValue == value }
                ?: throw KQuicError.ProtocolViolation(
                    "Unsupported TLS 1.3 handshake message type $value"
                )
    }
}

internal data class TlsHandshakeMessage(
    val type: TlsHandshakeType,
    val body: ByteArray
) {
    fun encode(): ByteArray {
        if (body.size > MAX_TLS_HANDSHAKE_MESSAGE_SIZE) {
            throw KQuicError.ProtocolViolation("TLS handshake message exceeds configured limit")
        }
        return byteArrayOf(
            type.wireValue.toByte(),
            (body.size ushr 16).toByte(),
            (body.size ushr 8).toByte(),
            body.size.toByte()
        ) + body
    }
}

/** Parses TLS handshake messages after CRYPTO offset reassembly. */
internal class TlsHandshakeFramer(
    private val maximumMessageSize: Int = MAX_TLS_HANDSHAKE_MESSAGE_SIZE
) {
    private var buffered = ByteArray(0)

    fun feed(bytes: ByteArray): List<TlsHandshakeMessage> {
        if (bytes.isNotEmpty()) buffered += bytes
        val messages = mutableListOf<TlsHandshakeMessage>()
        var position = 0
        while (buffered.size - position >= TLS_HANDSHAKE_HEADER_SIZE) {
            val size =
                ((buffered[position + 1].toInt() and 0xff) shl 16) or
                    ((buffered[position + 2].toInt() and 0xff) shl 8) or
                    (buffered[position + 3].toInt() and 0xff)
            if (size > maximumMessageSize) {
                throw KQuicError.ProtocolViolation("TLS handshake message exceeds configured limit")
            }
            val end = position + TLS_HANDSHAKE_HEADER_SIZE + size
            if (end > buffered.size) break
            messages += TlsHandshakeMessage(
                type = TlsHandshakeType.fromWire(buffered[position].toInt() and 0xff),
                body = buffered.copyOfRange(position + TLS_HANDSHAKE_HEADER_SIZE, end)
            )
            position = end
        }
        if (position > 0) buffered = buffered.copyOfRange(position, buffered.size)
        if (buffered.size > maximumMessageSize + TLS_HANDSHAKE_HEADER_SIZE) {
            throw KQuicError.ProtocolViolation("TLS handshake buffer exceeds configured limit")
        }
        return messages
    }
}

/**
 * TLS 1.3 SHA-256 key schedule used by the common handshake implementation.
 *
 * This is protocol composition around platform HMAC/SHA-256 primitives; no cryptographic
 * primitive is implemented in Kotlin.
 */
internal class Tls13KeySchedule(
    private val primitives: Tls13HashPrimitives
) {
    private val emptyHash = primitives.sha256(ByteArray(0))
    private val zeroSalt = ByteArray(SHA256_LENGTH)

    fun handshakeSecrets(
        ecdheSharedSecret: ByteArray,
        helloTranscript: ByteArray
    ): Tls13HandshakeSecrets {
        val earlySecret = extract(zeroSalt, zeroSalt)
        val derived = deriveSecret(earlySecret, "derived", emptyHash)
        val handshakeSecret = extract(derived, ecdheSharedSecret)
        val transcriptHash = primitives.sha256(helloTranscript)
        return Tls13HandshakeSecrets(
            handshakeSecret = handshakeSecret,
            clientHandshakeTrafficSecret = deriveSecret(
                handshakeSecret,
                "c hs traffic",
                transcriptHash
            ),
            serverHandshakeTrafficSecret = deriveSecret(
                handshakeSecret,
                "s hs traffic",
                transcriptHash
            )
        )
    }

    fun applicationSecrets(
        handshakeSecret: ByteArray,
        serverFinishedTranscript: ByteArray
    ): Tls13ApplicationSecrets {
        val derived = deriveSecret(handshakeSecret, "derived", emptyHash)
        val masterSecret = extract(derived, zeroSalt)
        val transcriptHash = primitives.sha256(serverFinishedTranscript)
        return Tls13ApplicationSecrets(
            masterSecret = masterSecret,
            clientApplicationTrafficSecret = deriveSecret(
                masterSecret,
                "c ap traffic",
                transcriptHash
            ),
            serverApplicationTrafficSecret = deriveSecret(
                masterSecret,
                "s ap traffic",
                transcriptHash
            )
        )
    }

    fun finishedVerifyData(baseTrafficSecret: ByteArray, transcript: ByteArray): ByteArray {
        val finishedKey = expandLabel(baseTrafficSecret, "finished", ByteArray(0), SHA256_LENGTH)
        return primitives.hmacSha256(finishedKey, primitives.sha256(transcript))
    }

    fun quicPacketProtectionKeys(trafficSecret: ByteArray): QuicPacketProtectionKeys =
        QuicPacketProtectionKeys(
            key = expandLabel(trafficSecret, "quic key", ByteArray(0), AES_128_KEY_LENGTH),
            iv = expandLabel(trafficSecret, "quic iv", ByteArray(0), QUIC_IV_LENGTH),
            headerProtectionKey = expandLabel(
                trafficSecret,
                "quic hp",
                ByteArray(0),
                AES_128_KEY_LENGTH
            )
        )

    fun deriveSecret(secret: ByteArray, label: String, transcriptHash: ByteArray): ByteArray =
        expandLabel(secret, label, transcriptHash, SHA256_LENGTH)

    private fun extract(salt: ByteArray, inputKeyMaterial: ByteArray): ByteArray =
        primitives.hmacSha256(salt, inputKeyMaterial)

    private fun expandLabel(
        secret: ByteArray,
        label: String,
        context: ByteArray,
        length: Int
    ): ByteArray {
        val fullLabel = "tls13 $label".encodeToByteArray()
        val info = ByteWriter().apply {
            byte(length ushr 8)
            byte(length and 0xff)
            byte(fullLabel.size)
            bytes(fullLabel)
            byte(context.size)
            bytes(context)
        }.build()
        return expand(secret, info, length)
    }

    private fun expand(secret: ByteArray, info: ByteArray, length: Int): ByteArray {
        var previous = ByteArray(0)
        var result = ByteArray(0)
        var counter = 1
        while (result.size < length) {
            previous = primitives.hmacSha256(
                secret,
                previous + info + byteArrayOf(counter.toByte())
            )
            result += previous
            counter++
        }
        return result.copyOf(length)
    }
}

internal data class Tls13HandshakeSecrets(
    val handshakeSecret: ByteArray,
    val clientHandshakeTrafficSecret: ByteArray,
    val serverHandshakeTrafficSecret: ByteArray
)

internal data class Tls13ApplicationSecrets(
    val masterSecret: ByteArray,
    val clientApplicationTrafficSecret: ByteArray,
    val serverApplicationTrafficSecret: ByteArray
)

private const val TLS_HANDSHAKE_HEADER_SIZE = 4
private const val MAX_TLS_HANDSHAKE_MESSAGE_SIZE = 1_048_576
private const val SHA256_LENGTH = 32
private const val AES_128_KEY_LENGTH = 16
private const val QUIC_IV_LENGTH = 12
