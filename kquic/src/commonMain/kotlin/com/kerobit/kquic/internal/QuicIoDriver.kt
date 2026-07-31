package com.kerobit.kquic.internal

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Coroutine and socket boundary for one QUIC connection. Protocol state is not allowed here; the
 * driver only turns platform I/O and time into serialized inputs for its owner.
 */
internal class QuicIoDriver(
    private val scope: CoroutineScope,
    private val socket: KQuicDatagramSocket,
    private val isClosing: () -> Boolean,
    private val onInbound: suspend (KQuicInboundDatagram) -> Unit,
    private val onTick: suspend () -> Unit,
    private val onFailure: (Throwable) -> Unit
) {
    private var receiveJob: Job? = null
    private var tickJob: Job? = null
    private var closed = false

    fun start() {
        if (receiveJob != null || closed) return
        receiveJob = scope.launch {
            try {
                while (isActive && !isClosing()) {
                    onInbound(socket.receive())
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!isClosing()) onFailure(error)
            }
        }
    }

    fun scheduleTick(delayMs: Long) {
        if (closed || isClosing()) return
        tickJob?.cancel()
        tickJob = scope.launch {
            delay(delayMs.coerceAtLeast(0))
            if (isActive && !isClosing()) onTick()
        }
    }

    suspend fun send(destination: KQuicSocketAddress, payload: ByteArray) {
        socket.send(destination, payload)
    }

    fun close() {
        if (closed) return
        closed = true
        receiveJob?.cancel()
        tickJob?.cancel()
        socket.close()
    }
}
