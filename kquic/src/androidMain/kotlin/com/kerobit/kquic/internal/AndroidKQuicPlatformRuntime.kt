package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicIdentityProvider
import com.kerobit.kquic.KQuicSignatureAlgorithm
import com.kerobit.kquic.KQuicSigner
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.net.NetworkInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal actual fun createKQuicPlatformRuntime(): KQuicPlatformRuntime =
    AndroidKQuicPlatformRuntime

private object AndroidKQuicPlatformRuntime : KQuicPlatformRuntime {
    override val udp = AndroidKQuicDatagramSocketFactory
    override val network = KQuicNetworkRuntime {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.toList() }
            .filter { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            .mapNotNull { it.hostAddress?.substringBefore('%') }
            .distinct()
    }
    override val clock = KQuicMonotonicClock(System::nanoTime)
    override val random = KQuicRandomRuntime { size ->
        ByteArray(size).also(SecureRandom()::nextBytes)
    }
    override val crypto = AndroidKQuicCryptoRuntime
}

private object AndroidKQuicCryptoRuntime : KQuicCryptoRuntime {
    override val ephemeralIdentityProvider = KQuicIdentityProvider {
        withContext(Dispatchers.Default) {
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
    }

    override val packetProtection: QuicCryptoPrimitives = AndroidQuicCryptoPrimitives
    override val handshakeCrypto: Tls13HandshakeCrypto = AndroidTls13HandshakeCrypto

    override fun sha256(data: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(data)
}
