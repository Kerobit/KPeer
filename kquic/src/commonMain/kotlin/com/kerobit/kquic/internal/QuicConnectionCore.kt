package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicChannelConfig
import com.kerobit.kquic.KQuicTransportEvent

internal class QuicConnectionCore(
    startedAtMs: Long,
    private val idleTimeoutMs: Long,
    private val networkCheckIntervalMs: Long
) {
    sealed interface Command {
        data class Receive(val datagram: KQuicInboundDatagram, val nowMs: Long) : Command
        data class Deadline(val nowMs: Long) : Command
        data class OpenChannel(val config: KQuicChannelConfig) : Command
        data class Send(
            val label: String,
            val payload: ByteArray,
            val text: Boolean,
            val reservedBytes: Int
        ) : Command
        data class CloseChannel(val label: String) : Command
        data class Close(val reason: String) : Command
    }

    sealed interface Effect {
        data class DatagramReady(val datagram: KQuicInboundDatagram) : Effect
        data class DatagramToSend(
            val destination: KQuicSocketAddress,
            val payload: ByteArray,
            val sentAtMs: Long
        ) : Effect
        data object IdleTimeout : Effect
        data object CheckNetwork : Effect
        data class OpenChannelRequested(val config: KQuicChannelConfig) : Effect
        data class SendRequested(
            val label: String,
            val payload: ByteArray,
            val text: Boolean,
            val reservedBytes: Int
        ) : Effect
        data class CloseChannelRequested(val label: String) : Effect
        data class CloseRequested(val reason: String) : Effect
        data class EventReady(val event: KQuicTransportEvent) : Effect
    }

    data class WireStats(val bytesSent: Long, val bytesReceived: Long, val packetsSent: Long, val packetsReceived: Long)

    private val lifecycle = QuicLifecycle(startedAtMs)
    private var bytesSent = 0L
    private var bytesReceived = 0L
    private var packetsSent = 0L
    private var packetsReceived = 0L

    val started: Boolean get() = lifecycle.started
    val closing: Boolean get() = lifecycle.closing
    val lastActivityMs: Long get() = lifecycle.lastActivityMs

    fun start(): Boolean = lifecycle.start()

    fun command(command: Command): List<Effect> = when (command) {
        is Command.Receive -> receive(command.datagram, command.nowMs)
        is Command.Deadline -> deadline(command.nowMs)
        is Command.OpenChannel -> active {
            Effect.OpenChannelRequested(command.config)
        }
        is Command.Send -> active {
            Effect.SendRequested(command.label, command.payload, command.text, command.reservedBytes)
        }
        is Command.CloseChannel -> active {
            Effect.CloseChannelRequested(command.label)
        }
        is Command.Close -> active { Effect.CloseRequested(command.reason) }
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

    fun event(event: KQuicTransportEvent): Effect.EventReady = Effect.EventReady(event)

    fun nextDeadlineMs(): Long? {
        if (!started || closing) return null
        return minOf(lifecycle.lastActivityMs + idleTimeoutMs, lifecycle.lastNetworkCheckAtMs + networkCheckIntervalMs)
    }

    fun stats(): WireStats = WireStats(
        bytesSent = bytesSent,
        bytesReceived = bytesReceived,
        packetsSent = packetsSent,
        packetsReceived = packetsReceived
    )

    private fun receive(datagram: KQuicInboundDatagram, nowMs: Long): List<Effect> {
        if (closing) return emptyList()
        lifecycle.activity(nowMs)
        bytesReceived += datagram.payload.size
        packetsReceived++
        return listOf(Effect.DatagramReady(datagram))
    }

    private inline fun active(effect: () -> Effect): List<Effect> =
        if (closing) emptyList() else listOf(effect())
}
