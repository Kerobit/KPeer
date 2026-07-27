package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicIdentityProvider

/**
 * Minimal platform surface consumed by the common rendezvous layer.
 *
 * UDP, clocks, randomness and cryptographic primitives are added to this boundary as the packet
 * engine is wired. Protocol state never belongs in an actual implementation.
 */
internal interface KQuicPlatformServices {
    val ephemeralIdentityProvider: KQuicIdentityProvider
    val datagramSocketFactory: KQuicDatagramSocketFactory?
    val cryptoPrimitives: QuicCryptoPrimitives?
    val handshakeCrypto: Tls13HandshakeCrypto?
    val monotonicClock: KQuicMonotonicClock
    fun secureRandom(size: Int): ByteArray
    fun hostAddresses(): List<String>
    fun sha256(data: ByteArray): ByteArray
}

internal fun interface KQuicMonotonicClock {
    fun nowNanos(): Long
}

internal object UnavailableKQuicPlatformServices : KQuicPlatformServices {
    override val ephemeralIdentityProvider = KQuicIdentityProvider {
        throw KQuicError.TransportUnavailable()
    }
    override val datagramSocketFactory: KQuicDatagramSocketFactory? = null
    override val cryptoPrimitives: QuicCryptoPrimitives? = null
    override val handshakeCrypto: Tls13HandshakeCrypto? = null
    override val monotonicClock = KQuicMonotonicClock {
        throw KQuicError.TransportUnavailable()
    }
    override fun secureRandom(size: Int): ByteArray = throw KQuicError.TransportUnavailable()
    override fun hostAddresses(): List<String> = emptyList()
    override fun sha256(data: ByteArray): ByteArray = throw KQuicError.TransportUnavailable()
}

internal expect fun createKQuicPlatformServices(platformContext: Any?): KQuicPlatformServices
