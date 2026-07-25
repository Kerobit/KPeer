package com.kerobit.kquic.internal

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.KeyAgreement

internal object JvmTls13HandshakeCrypto : Tls13HandshakeCrypto {
    private val random = SecureRandom()
    override val primitives: QuicCryptoPrimitives = JvmQuicCryptoPrimitives

    override fun createP256KeyAgreement(): Tls13KeyAgreement {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), random)
            generateKeyPair()
        }
        val localPublic = keyPair.public as ECPublicKey
        return object : Tls13KeyAgreement {
            override val publicKey = byteArrayOf(0x04) +
                localPublic.w.affineX.toUnsignedFixed(32) +
                localPublic.w.affineY.toUnsignedFixed(32)

            override fun deriveSharedSecret(peerPublicKey: ByteArray): ByteArray {
                require(peerPublicKey.size == 65 && peerPublicKey[0] == 0x04.toByte())
                val point = ECPoint(
                    BigInteger(1, peerPublicKey.copyOfRange(1, 33)),
                    BigInteger(1, peerPublicKey.copyOfRange(33, 65))
                )
                val peer = KeyFactory.getInstance("EC").generatePublic(
                    ECPublicKeySpec(point, localPublic.params)
                )
                return KeyAgreement.getInstance("ECDH").run {
                    init(keyPair.private)
                    doPhase(peer, true)
                    generateSecret()
                }
            }
        }
    }

    override fun secureRandom(size: Int): ByteArray =
        ByteArray(size).also(random::nextBytes)

    override fun verifyEcdsaP256Sha256(
        subjectPublicKeyInfoDer: ByteArray,
        message: ByteArray,
        signature: ByteArray
    ): Boolean = Signature.getInstance("SHA256withECDSA").run {
        initVerify(
            KeyFactory.getInstance("EC").generatePublic(
                X509EncodedKeySpec(subjectPublicKeyInfoDer)
            )
        )
        update(message)
        verify(signature)
    }
}

private fun BigInteger.toUnsignedFixed(size: Int): ByteArray {
    val encoded = toByteArray()
    val unsigned = if (encoded.size > size && encoded.first() == 0.toByte()) {
        encoded.copyOfRange(1, encoded.size)
    } else {
        encoded
    }
    require(unsigned.size <= size)
    return ByteArray(size - unsigned.size) + unsigned
}
