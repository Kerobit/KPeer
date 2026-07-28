package com.kerobit.kquic.internal

/**
 * Deterministic connection shell. It owns lifecycle, deadlines and wire counters while the actor
 * executes its effects. There are deliberately no sockets, coroutines, flows or system-clock
 * reads in this type: time and datagrams are explicit inputs.
 */
internal class QuicConnectionCore(
    startedAtMs: Long,
    private val idleTimeoutMs: Long,
    private val networkCheckIntervalMs: Long
) {
    sealed interface Effect {
        data class DatagramReady(val datagram: KQuicInboundDatagram) : Effect
        data class DatagramToSend(
            val destination: KQuicSocketAddress,
            val payload: ByteArray,
            val sentAtMs: Long
        ) : Effect
        data object IdleTimeout : Effect
        data object CheckNetwork : Effect
        data class CloseRequested(val reason: String) : Effect
    }

    data class WireStats(
        val bytesSent: Long,
        val bytesReceived: Long,
        val packetsSent: Long,
        val packetsReceived: Long
    )

    private val lifecycle = QuicLifecycle(startedAtMs)
    private var bytesSent = 0L
    private var bytesReceived = 0L
    private var packetsSent = 0L
    private var packetsReceived = 0L

    val started: Boolean get() = lifecycle.started
    val closing: Boolean get() = lifecycle.closing
    val lastActivityMs: Long get() = lifecycle.lastActivityMs

    fun start(): Boolean = lifecycle.start()

    fun receive(datagram: KQuicInboundDatagram, nowMs: Long): List<Effect> {
        if (closing) return emptyList()
        lifecycle.activity(nowMs)
        bytesReceived += datagram.payload.size
        packetsReceived++
        return listOf(Effect.DatagramReady(datagram))
    }

    fun transmit(
        destination: KQuicSocketAddress,
        payload: ByteArray,
        nowMs: Long
    ): Effect.DatagramToSend? {
        if (closing) return null
        lifecycle.activity(nowMs)
        bytesSent += payload.size
        packetsSent++
        return Effect.DatagramToSend(destination, payload, lifecycle.lastActivityMs)
    }

    fun deadline(nowMs: Long): List<Effect> {
        if (closing) return emptyList()
        val effects = mutableListOf<Effect>()
        if (lifecycle.shouldIdleTimeout(nowMs, idleTimeoutMs)) {
            effects += Effect.IdleTimeout
        }
        if (lifecycle.shouldCheckNetwork(nowMs, networkCheckIntervalMs)) {
            lifecycle.networkChecked(nowMs)
            effects += Effect.CheckNetwork
        }
        return effects
    }

    fun close(reason: String): List<Effect> =
        if (lifecycle.beginClose()) listOf(Effect.CloseRequested(reason)) else emptyList()

    fun nextDeadlineMs(): Long? {
        if (!started || closing) return null
        return minOf(
            lifecycle.lastActivityMs + idleTimeoutMs,
            lifecycle.lastNetworkCheckAtMs + networkCheckIntervalMs
        )
    }

    fun stats(): WireStats = WireStats(
        bytesSent = bytesSent,
        bytesReceived = bytesReceived,
        packetsSent = packetsSent,
        packetsReceived = packetsReceived
    )
}
