package com.kerobit.kquic.internal

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.AF_INET6
import platform.posix.IFF_LOOPBACK
import platform.posix.IFF_UP
import platform.posix.NI_MAXHOST
import platform.posix.NI_NUMERICHOST
import platform.posix.getnameinfo
import platform.posix.sockaddr_in
import platform.posix.sockaddr_in6
import platform.darwin.freeifaddrs
import platform.darwin.getifaddrs
import platform.darwin.ifaddrs

@OptIn(ExperimentalForeignApi::class)
internal fun appleHostAddresses(): List<String> = memScoped {
    val interfaces = alloc<CPointerVar<ifaddrs>>()
    if (getifaddrs(interfaces.ptr) != 0) return@memScoped emptyList()
    try {
        buildList {
            var current = interfaces.value
            while (current != null) {
                val item = current.pointed
                val address = item.ifa_addr
                val isUp = (item.ifa_flags.toInt() and IFF_UP) != 0
                val isLoopback = (item.ifa_flags.toInt() and IFF_LOOPBACK) != 0
                val family = address?.pointed?.sa_family?.toInt()
                if (address != null && isUp && !isLoopback &&
                    (family == AF_INET || family == AF_INET6)
                ) {
                    val host = allocArray<ByteVar>(NI_MAXHOST)
                    val length = if (family == AF_INET) {
                        sizeOf<sockaddr_in>()
                    } else {
                        sizeOf<sockaddr_in6>()
                    }
                    if (getnameinfo(
                            address,
                            length.convert(),
                            host,
                            NI_MAXHOST.convert(),
                            null,
                            0u,
                            NI_NUMERICHOST
                        ) == 0
                    ) {
                        val value = host.toKString().substringBefore('%')
                        if (!value.startsWith("fe80:", ignoreCase = true)) add(value)
                    }
                }
                current = item.ifa_next
            }
        }.distinct()
    } finally {
        freeifaddrs(interfaces.value)
    }
}
