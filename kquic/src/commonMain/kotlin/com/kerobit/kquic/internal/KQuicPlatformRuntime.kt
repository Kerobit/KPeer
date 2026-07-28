package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicIdentityProvider

/**
 * Minimal platform surface consumed by the common rendezvous layer.
 *
 * UDP, clocks, randomness and cryptographic primitives are added to this boundary as the packet
 * engine is wired. Protocol state never belongs in an actual implementation.
 */
internal interface KQuicPlatformRuntime {
    val udp: KQuicDatagramSocketFactory
    val network: KQuicNetworkRuntime
    val clock: KQuicMonotonicClock
    val random: KQuicRandomRuntime
    val crypto: KQuicCryptoRuntime
}

internal fun interface KQuicNetworkRuntime {
    fun hostAddresses(): List<String>
}

internal fun interface KQuicRandomRuntime {
    fun bytes(size: Int): ByteArray
}

internal interface KQuicCryptoRuntime {
    val ephemeralIdentityProvider: KQuicIdentityProvider
    val packetProtection: QuicCryptoPrimitives
    val handshakeCrypto: Tls13HandshakeCrypto
    fun sha256(data: ByteArray): ByteArray
}

internal fun interface KQuicMonotonicClock {
    fun nowNanos(): Long
}

internal expect fun createKQuicPlatformRuntime(): KQuicPlatformRuntime
