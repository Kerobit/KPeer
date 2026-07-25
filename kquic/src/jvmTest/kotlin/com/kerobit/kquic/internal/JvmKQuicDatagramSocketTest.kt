package com.kerobit.kquic.internal

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class JvmKQuicDatagramSocketTest {
    @Test
    fun bound_sockets_exchange_udp_datagrams_in_both_directions() = runBlocking {
        val left = JvmKQuicDatagramSocketFactory.bind(KQuicSocketAddress("127.0.0.1", 0))
        val right = JvmKQuicDatagramSocketFactory.bind(KQuicSocketAddress("127.0.0.1", 0))
        try {
            val receivedByRight = async { right.receive() }
            left.send(right.localAddress, byteArrayOf(1, 2, 3))
            val first = withTimeout(1_000) { receivedByRight.await() }
            assertEquals(left.localAddress.port, first.source.port)
            assertContentEquals(byteArrayOf(1, 2, 3), first.payload)

            val receivedByLeft = async { left.receive() }
            right.send(left.localAddress, byteArrayOf(9, 8))
            val second = withTimeout(1_000) { receivedByLeft.await() }
            assertEquals(right.localAddress.port, second.source.port)
            assertContentEquals(byteArrayOf(9, 8), second.payload)
        } finally {
            left.close()
            right.close()
        }
    }
}
