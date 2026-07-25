package com.kerobit.kquic.internal

import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object AndroidKQuicDatagramSocketFactory : KQuicDatagramSocketFactory {
    override suspend fun bind(localAddress: KQuicSocketAddress): KQuicDatagramSocket =
        withContext(Dispatchers.IO) {
            val channel = DatagramChannel.open()
            try {
                channel.configureBlocking(true)
                channel.bind(InetSocketAddress(localAddress.host, localAddress.port))
                AndroidKQuicDatagramSocket(channel)
            } catch (error: Throwable) {
                channel.close()
                throw error
            }
        }
}

private class AndroidKQuicDatagramSocket(
    private val channel: DatagramChannel
) : KQuicDatagramSocket {
    private val closed = AtomicBoolean(false)
    override val localAddress: KQuicSocketAddress = channel.localAddress.toKQuicAddress()

    override suspend fun send(destination: KQuicSocketAddress, payload: ByteArray) {
        require(payload.size <= MAX_UDP_PAYLOAD_SIZE) {
            "UDP payload exceeds $MAX_UDP_PAYLOAD_SIZE bytes"
        }
        withContext(Dispatchers.IO) {
            check(!closed.get()) { "UDP socket is closed" }
            channel.send(
                ByteBuffer.wrap(payload),
                InetSocketAddress(destination.host, destination.port)
            )
        }
    }

    override suspend fun receive(): KQuicInboundDatagram = withContext(Dispatchers.IO) {
        check(!closed.get()) { "UDP socket is closed" }
        val buffer = ByteBuffer.allocate(MAX_UDP_DATAGRAM_SIZE)
        val source = channel.receive(buffer) as InetSocketAddress
        buffer.flip()
        val payload = ByteArray(buffer.remaining())
        buffer.get(payload)
        KQuicInboundDatagram(source.toKQuicAddress(), payload)
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            channel.close()
        }
    }
}

private fun java.net.SocketAddress.toKQuicAddress(): KQuicSocketAddress {
    val address = this as InetSocketAddress
    return KQuicSocketAddress(
        host = address.address?.hostAddress ?: address.hostString,
        port = address.port
    )
}

private const val MAX_UDP_PAYLOAD_SIZE = 65_507
private const val MAX_UDP_DATAGRAM_SIZE = 65_535
