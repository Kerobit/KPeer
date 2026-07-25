package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError

internal enum class QuicInitialRole { CLIENT, SERVER }

internal data class QuicPacketProtectionKeys(
    val key: ByteArray,
    val iv: ByteArray,
    val headerProtectionKey: ByteArray
)

/** Cryptographic operations supplied by reviewed platform providers, never implemented here. */
internal interface QuicCryptoPrimitives {
    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray
    fun aesEcbEncrypt(key: ByteArray, block: ByteArray): ByteArray
    fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray): ByteArray
    fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray
}

/**
 * RFC 9001 Initial key derivation and packet protection.
 *
 * Initial keys are public by design and only protect the first handshake flight from
 * ossification. Authentication comes later from TLS certificates and Finished.
 */
internal class QuicInitialCrypto(
    private val primitives: QuicCryptoPrimitives
) {
    fun derive(destinationConnectionId: ByteArray, role: QuicInitialRole): QuicPacketProtectionKeys {
        val initialSecret = hkdfExtract(QUIC_V1_INITIAL_SALT, destinationConnectionId)
        val directionalSecret = hkdfExpandLabel(
            secret = initialSecret,
            label = if (role == QuicInitialRole.CLIENT) "client in" else "server in",
            length = SHA256_LENGTH
        )
        return QuicPacketProtectionKeys(
            key = hkdfExpandLabel(directionalSecret, "quic key", AES_128_KEY_LENGTH),
            iv = hkdfExpandLabel(directionalSecret, "quic iv", QUIC_IV_LENGTH),
            headerProtectionKey = hkdfExpandLabel(
                directionalSecret,
                "quic hp",
                AES_128_KEY_LENGTH
            )
        )
    }

    fun seal(
        keys: QuicPacketProtectionKeys,
        packetNumber: ULong,
        unprotectedHeader: ByteArray,
        plaintext: ByteArray
    ): ByteArray = primitives.aesGcmSeal(
        key = keys.key,
        nonce = nonce(keys.iv, packetNumber),
        aad = unprotectedHeader,
        plaintext = plaintext
    )

    fun open(
        keys: QuicPacketProtectionKeys,
        packetNumber: ULong,
        unprotectedHeader: ByteArray,
        ciphertext: ByteArray
    ): ByteArray = try {
        primitives.aesGcmOpen(
            key = keys.key,
            nonce = nonce(keys.iv, packetNumber),
            aad = unprotectedHeader,
            ciphertext = ciphertext
        )
    } catch (_: Exception) {
        throw KQuicError.ProtocolViolation("QUIC packet authentication failed")
    }

    fun headerProtectionMask(keys: QuicPacketProtectionKeys, sample: ByteArray): ByteArray {
        if (sample.size != AES_BLOCK_LENGTH) {
            throw KQuicError.ProtocolViolation("QUIC header-protection sample must be 16 bytes")
        }
        return primitives.aesEcbEncrypt(keys.headerProtectionKey, sample).copyOf(5)
    }

    private fun hkdfExtract(salt: ByteArray, inputKeyMaterial: ByteArray): ByteArray =
        primitives.hmacSha256(salt, inputKeyMaterial)

    private fun hkdfExpandLabel(secret: ByteArray, label: String, length: Int): ByteArray {
        val fullLabel = "tls13 $label".encodeToByteArray()
        val info = ByteWriter().apply {
            byte(length ushr 8)
            byte(length and 0xff)
            byte(fullLabel.size)
            bytes(fullLabel)
            byte(0)
        }.build()
        return hkdfExpand(secret, info, length)
    }

    private fun hkdfExpand(secret: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 1..(255 * SHA256_LENGTH))
        var previous = ByteArray(0)
        var output = ByteArray(0)
        var counter = 1
        while (output.size < length) {
            previous = primitives.hmacSha256(secret, previous + info + byteArrayOf(counter.toByte()))
            output += previous
            counter++
        }
        return output.copyOf(length)
    }

    private fun nonce(iv: ByteArray, packetNumber: ULong): ByteArray {
        require(iv.size == QUIC_IV_LENGTH)
        val nonce = iv.copyOf()
        repeat(8) { index ->
            val position = nonce.lastIndex - index
            nonce[position] = (
                nonce[position].toInt() xor
                    ((packetNumber shr (index * 8)).toInt() and 0xff)
                ).toByte()
        }
        return nonce
    }
}

private val QUIC_V1_INITIAL_SALT = byteArrayOf(
    0x38, 0x76, 0x2c, 0xf7.toByte(), 0xf5.toByte(),
    0x59, 0x34, 0xb3.toByte(), 0x4d, 0x17,
    0x9a.toByte(), 0xe6.toByte(), 0xa4.toByte(), 0xc8.toByte(), 0x0c,
    0xad.toByte(), 0xcc.toByte(), 0xbb.toByte(), 0x7f, 0x0a
)
private const val SHA256_LENGTH = 32
private const val AES_128_KEY_LENGTH = 16
private const val QUIC_IV_LENGTH = 12
private const val AES_BLOCK_LENGTH = 16
