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

internal object AndroidTls13HandshakeCrypto : Tls13HandshakeCrypto {
    private val random = SecureRandom()
    override val primitives: QuicCryptoPrimitives = AndroidQuicCryptoPrimitives

    override fun createP256KeyAgreement(): Tls13KeyAgreement {
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), random)
            generateKeyPair()
        }
        val localPublic = keyPair.public as ECPublicKey
        return object : Tls13KeyAgreement {
            override val publicKey: ByteArray = localPublic.toUncompressedPoint()

            override fun deriveSharedSecret(peerPublicKey: ByteArray): ByteArray {
                val peer = KeyFactory.getInstance("EC").generatePublic(
                    ECPublicKeySpec(peerPublicKey.toPoint(), localPublic.params)
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
    ): Boolean {
        val publicKey = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(subjectPublicKeyInfoDer))
        return Signature.getInstance("SHA256withECDSA").run {
            initVerify(publicKey)
            update(message)
            verify(signature)
        }
    }
}

private fun ECPublicKey.toUncompressedPoint(): ByteArray =
    byteArrayOf(0x04) +
        w.affineX.toUnsignedFixed(32) +
        w.affineY.toUnsignedFixed(32)

private fun ByteArray.toPoint(): ECPoint {
    require(size == 65 && first() == 0x04.toByte())
    return ECPoint(BigInteger(1, copyOfRange(1, 33)), BigInteger(1, copyOfRange(33, 65)))
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
