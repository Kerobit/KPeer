package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicIdentityProvider
import com.kerobit.kquic.KQuicSignatureAlgorithm
import com.kerobit.kquic.KQuicSigner
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.net.NetworkInterface
import java.security.spec.ECGenParameterSpec

internal actual fun createKQuicPlatformServices(platformContext: Any?): KQuicPlatformServices =
    JvmKQuicPlatformServices

private object JvmKQuicPlatformServices : KQuicPlatformServices by
    UnavailableKQuicPlatformServices {
    override val ephemeralIdentityProvider = KQuicIdentityProvider {
        val random = SecureRandom()
        val keyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"), random)
            generateKeyPair()
        }
        val signer = KQuicSigner { algorithm, message ->
            require(algorithm == KQuicSignatureAlgorithm.ECDSA_SECP256R1_SHA256)
            Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private, random)
                update(message)
                sign()
            }
        }
        createEphemeralKQuicIdentity(
            subjectPublicKeyInfoDer = keyPair.public.encoded,
            randomSerial = ByteArray(16).also(random::nextBytes),
            sha256 = { MessageDigest.getInstance("SHA-256").digest(it) },
            signer = signer
        )
    }
    override val datagramSocketFactory: KQuicDatagramSocketFactory =
        JvmKQuicDatagramSocketFactory
    override val cryptoPrimitives: QuicCryptoPrimitives = JvmQuicCryptoPrimitives
    override val handshakeCrypto: Tls13HandshakeCrypto = JvmTls13HandshakeCrypto
    override val monotonicClock = KQuicMonotonicClock(System::nanoTime)
    override fun secureRandom(size: Int): ByteArray =
        ByteArray(size).also(SecureRandom()::nextBytes)
    override fun hostAddresses(): List<String> =
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct()
    override fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
