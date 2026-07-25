package com.kerobit.kquic.internal

internal data class KQuicSocketAddress(
    val host: String,
    val port: Int
) {
    init {
        require(host.isNotBlank())
        require(port in 0..65_535)
    }
}

internal data class KQuicInboundDatagram(
    val source: KQuicSocketAddress,
    val payload: ByteArray
)

/**
 * Small UDP boundary used by the platform QUIC engine. Implementations must make [close]
 * idempotent and unblock a pending [receive].
 */
internal interface KQuicDatagramSocket {
    val localAddress: KQuicSocketAddress

    suspend fun send(destination: KQuicSocketAddress, payload: ByteArray)
    suspend fun receive(): KQuicInboundDatagram
    fun close()
}

internal fun interface KQuicDatagramSocketFactory {
    suspend fun bind(localAddress: KQuicSocketAddress): KQuicDatagramSocket
}
