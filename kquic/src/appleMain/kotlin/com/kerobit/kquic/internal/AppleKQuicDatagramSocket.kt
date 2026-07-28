package com.kerobit.kquic.internal

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.pointed
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.AF_UNSPEC
import platform.posix.EAGAIN
import platform.posix.EINTR
import platform.posix.EWOULDBLOCK
import platform.posix.F_GETFL
import platform.posix.F_SETFL
import platform.posix.IPPROTO_UDP
import platform.posix.NI_MAXHOST
import platform.posix.NI_MAXSERV
import platform.posix.NI_NUMERICHOST
import platform.posix.NI_NUMERICSERV
import platform.posix.O_NONBLOCK
import platform.posix.SOCK_DGRAM
import platform.posix.addrinfo
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.fcntl
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo
import platform.posix.getsockname
import platform.posix.recvfrom
import platform.posix.sendto
import platform.posix.sockaddr
import platform.posix.sockaddr_storage
import platform.posix.socket
import platform.posix.socklen_tVar

@OptIn(ExperimentalForeignApi::class)
internal object AppleKQuicDatagramSocketFactory : KQuicDatagramSocketFactory {
    override suspend fun bind(localAddress: KQuicSocketAddress): KQuicDatagramSocket =
        withContext(Dispatchers.Default) {
            val bound = resolve(localAddress, passive = true) { address ->
                val descriptor = socket(address.ai_family, address.ai_socktype, address.ai_protocol)
                if (descriptor < 0) return@resolve null
                if (bind(descriptor, address.ai_addr, address.ai_addrlen) != 0) {
                    close(descriptor)
                    return@resolve null
                }
                val flags = fcntl(descriptor, F_GETFL)
                if (flags < 0 || fcntl(descriptor, F_SETFL, flags or O_NONBLOCK) != 0) {
                    close(descriptor)
                    return@resolve null
                }
                descriptor
            } ?: error("Unable to bind UDP socket at ${localAddress.host}:${localAddress.port}")
            AppleKQuicDatagramSocket(bound, socketAddress(bound))
        }
}

@OptIn(ExperimentalForeignApi::class)
private class AppleKQuicDatagramSocket(
    private val descriptor: Int,
    override val localAddress: KQuicSocketAddress
) : KQuicDatagramSocket {
    private var closed = false

    override suspend fun send(destination: KQuicSocketAddress, payload: ByteArray) {
        require(payload.size <= MAX_UDP_PAYLOAD_SIZE)
        withContext(Dispatchers.Default) {
            check(!closed) { "UDP socket is closed" }
            val sent = resolve(destination, passive = false) { address ->
                payload.usePinned { pinned ->
                    sendto(descriptor, pinned.addressOf(0), payload.size.convert(), 0, address.ai_addr, address.ai_addrlen)
                }
            } ?: -1
            if (sent != payload.size.toLong()) {
                error("UDP sendto failed with errno $errno")
            }
        }
    }

    override suspend fun receive(): KQuicInboundDatagram = withContext(Dispatchers.Default) {
        val payload = ByteArray(MAX_UDP_DATAGRAM_SIZE)
        while (true) {
            check(!closed) { "UDP socket is closed" }
            val result = memScoped {
                val source = alloc<sockaddr_storage>()
                val sourceLength = alloc<socklen_tVar>()
                sourceLength.value = sizeOf<sockaddr_storage>().convert()
                val received = payload.usePinned { pinned ->
                    recvfrom(
                        descriptor,
                        pinned.addressOf(0),
                        payload.size.convert(),
                        0,
                        source.ptr.reinterpret(),
                        sourceLength.ptr
                    )
                }
                if (received >= 0) {
                    KQuicInboundDatagram(
                        source = socketAddress(source.ptr.reinterpret(), sourceLength.value),
                        payload = payload.copyOf(received.toInt())
                    )
                } else {
                    null
                }
            }
            if (result != null) return@withContext result
            if (errno != EAGAIN && errno != EWOULDBLOCK && errno != EINTR) {
                error("UDP recvfrom failed with errno $errno")
            }
            delay(RECEIVE_POLL_DELAY_MS)
        }
        error("unreachable")
    }

    override fun close() {
        if (!closed) {
            closed = true
            close(descriptor)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private inline fun <T> resolve(
    address: KQuicSocketAddress,
    passive: Boolean,
    block: (addrinfo) -> T?
): T? = memScoped {
    val hints = alloc<addrinfo>()
    hints.ai_family = when {
        ':' in address.host -> AF_INET6
        address.host == "0.0.0.0" -> AF_INET
        else -> AF_UNSPEC
    }
    hints.ai_socktype = SOCK_DGRAM
    hints.ai_protocol = IPPROTO_UDP
    hints.ai_flags = if (passive) platform.posix.AI_PASSIVE else 0
    val result = alloc<CPointerVar<addrinfo>>()
    val status = getaddrinfo(address.host, address.port.toString(), hints.ptr, result.ptr)
    if (status != 0) return@memScoped null
    try {
        var current: CPointer<addrinfo>? = result.value
        while (current != null) {
            block(current.pointed)?.let { return@memScoped it }
            current = current.pointed.ai_next
        }
        null
    } finally {
        freeaddrinfo(result.value)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun socketAddress(descriptor: Int): KQuicSocketAddress = memScoped {
    val address = alloc<sockaddr_storage>()
    val length = alloc<socklen_tVar>()
    length.value = sizeOf<sockaddr_storage>().convert()
    check(getsockname(descriptor, address.ptr.reinterpret(), length.ptr) == 0) {
        "getsockname failed with errno $errno"
    }
    socketAddress(address.ptr.reinterpret(), length.value)
}

@OptIn(ExperimentalForeignApi::class)
private fun socketAddress(address: CPointer<sockaddr>, length: UInt): KQuicSocketAddress =
    memScoped {
        val host = allocArray<ByteVar>(NI_MAXHOST)
        val service = allocArray<ByteVar>(NI_MAXSERV)
        check(
            getnameinfo(
                address,
                length,
                host,
                NI_MAXHOST.convert(),
                service,
                NI_MAXSERV.convert(),
                NI_NUMERICHOST or NI_NUMERICSERV
            ) == 0
        ) { "getnameinfo failed" }
        KQuicSocketAddress(host.toKString(), service.toKString().toInt())
    }

private const val MAX_UDP_PAYLOAD_SIZE = 65_507
private const val MAX_UDP_DATAGRAM_SIZE = 65_535
private const val RECEIVE_POLL_DELAY_MS = 1L
