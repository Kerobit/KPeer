package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicConfig
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

@OptIn(ExperimentalAtomicApi::class)
internal class QuicFlowController(
    private val endpointRole: QuicEndpointRole,
    private val config: KQuicConfig,
    private val queuedFrameBytes: () -> Int,
    private val enqueue: (QuicFrame, Boolean) -> Boolean,
    private val bufferedAmountDrained: (String, Int) -> Unit
) {
    data class ReceiveCredit(
        val connectionMaximum: ULong?,
        val streamMaximum: ULong?
    )

    private data class PendingWrite(
        val streamId: ULong,
        val label: String,
        val bytes: ByteArray,
        val fin: Boolean,
        val reservedBytes: Int,
        val bufferedBytes: Int,
        var position: Int = 0
    )

    private val senders = mutableMapOf<ULong, QuicStreamSender>()
    private val pendingWrites = mutableListOf<PendingWrite>()
    private val streamDataConsumed = mutableMapOf<ULong, ULong>()
    private val pendingApplicationBytes = AtomicInt(0)
    private var remoteParameters: QuicTransportParameterValues? = null
    private var peerMaxConnectionData = 0uL
    private var peerConnectionDataSent = 0uL
    private var localConnectionDataConsumed = 0uL
    private var localConnectionMaximum = config.limits.maxConnectionData.toULong()
    private val localStreamMaximum = mutableMapOf<ULong, ULong>()

    var streamsBlocked: Long = 0
        private set

    fun reserve(payloadSize: Int): Int? {
        if (payloadSize > config.limits.maxMessageSize) return null
        val reservation = payloadSize + APPLICATION_MESSAGE_OVERHEAD
        while (true) {
            val current = pendingApplicationBytes.load()
            if (current + reservation > config.limits.maxSendQueueBytes) return null
            if (pendingApplicationBytes.compareAndSet(current, current + reservation)) {
                return reservation
            }
        }
    }

    fun release(reservedBytes: Int) {
        if (reservedBytes > 0) pendingApplicationBytes.fetchAndAdd(-reservedBytes)
    }

    fun applyRemoteParameters(parameters: QuicTransportParameterValues) {
        remoteParameters = parameters
        peerMaxConnectionData = parameters.initialMaxData
    }

    fun updatePeerMaxStreamData(streamId: ULong, maximum: ULong) {
        senders[streamId]?.updateMaximumData(maximum)
        drain()
    }

    fun updatePeerMaxData(maximum: ULong) {
        peerMaxConnectionData = maxOf(peerMaxConnectionData, maximum)
        drain()
    }

    fun consume(streamId: ULong, bytes: Int): ReceiveCredit {
        localConnectionDataConsumed += bytes.toULong()
        val streamConsumed = (streamDataConsumed[streamId] ?: 0uL) + bytes.toULong()
        streamDataConsumed[streamId] = streamConsumed
        val connectionTarget =
            config.limits.maxConnectionData.toULong() + localConnectionDataConsumed
        val streamTarget = config.limits.maxStreamData.toULong() + streamConsumed
        return ReceiveCredit(
            connectionMaximum = connectionTarget.takeIf {
                it - localConnectionMaximum >= receiveThreshold(config.limits.maxConnectionData)
            }?.also { localConnectionMaximum = it },
            streamMaximum = streamTarget.takeIf {
                it - (localStreamMaximum[streamId]
                    ?: config.limits.maxStreamData.toULong()) >=
                    receiveThreshold(config.limits.maxStreamData)
            }?.also { localStreamMaximum[streamId] = it }
        )
    }

    fun connectionCreditFrame(): QuicFrame.MaxData {
        localConnectionMaximum =
            config.limits.maxConnectionData.toULong() + localConnectionDataConsumed
        return QuicFrame.MaxData(localConnectionMaximum)
    }

    fun streamCreditFrame(streamId: ULong): QuicFrame.MaxStreamData {
        val maximum = config.limits.maxStreamData.toULong() +
            (streamDataConsumed[streamId] ?: 0uL)
        localStreamMaximum[streamId] = maximum
        return QuicFrame.MaxStreamData(streamId, maximum)
    }

    fun queue(
        streamId: ULong,
        label: String,
        bytes: ByteArray,
        fin: Boolean = false,
        reservedBytes: Int = 0,
        bufferedBytes: Int = 0
    ) {
        pendingWrites += PendingWrite(streamId, label, bytes, fin, reservedBytes, bufferedBytes)
        drain()
    }

    fun drain() {
        var index = 0
        while (index < pendingWrites.size) {
            val pending = pendingWrites[index]
            if (pendingWrites.take(index).any { it.streamId == pending.streamId }) {
                index++
                continue
            }
            val sender = senders.getOrPut(pending.streamId) {
                QuicStreamSender(pending.streamId, initialPeerStreamCredit(pending.streamId))
            }
            val remaining = pending.bytes.size - pending.position
            val connectionCredit = peerMaxConnectionData - peerConnectionDataSent
            val available = minOf(
                minOf(remaining.toULong(), sender.availableData),
                minOf(connectionCredit, QUIC_MAX_FRAME_PAYLOAD.toULong())
            ).toInt()
            val emptyFin = remaining == 0 && pending.fin
            if (available == 0 && !emptyFin) {
                recordBlocked(pending.streamId, sender, connectionCredit)
                index++
                continue
            }
            if (queuedFrameBytes() + available + QUIC_PACKET_OVERHEAD * 2 >
                config.limits.maxSendQueueBytes
            ) {
                return
            }
            val end = pending.position + available
            val frame = sender.frame(pending.bytes.copyOfRange(pending.position, end), pending.fin && end == pending.bytes.size)
            check(enqueue(frame, true))
            peerConnectionDataSent += available.toULong()
            pending.position = end
            if (end == pending.bytes.size) {
                pendingWrites.removeAt(index)
                release(pending.reservedBytes)
                if (pending.bufferedBytes > 0) {
                    bufferedAmountDrained(pending.label, pending.bufferedBytes)
                }
            } else {
                index++
            }
        }
    }

    fun peerBlockedOnStreams() {
        streamsBlocked++
    }

    fun reset() {
        pendingApplicationBytes.store(0)
        pendingWrites.clear()
    }

    private fun recordBlocked(
        streamId: ULong,
        sender: QuicStreamSender,
        connectionCredit: ULong
    ) {
        streamsBlocked++
        if (connectionCredit == 0uL) {
            enqueue(QuicFrame.DataBlocked(peerMaxConnectionData), false)
        }
        if (sender.availableData == 0uL) {
            enqueue(QuicFrame.StreamDataBlocked(streamId, sender.maximumDataLimit), false)
        }
    }

    private fun initialPeerStreamCredit(streamId: ULong): ULong {
        val parameters = remoteParameters ?: return 0uL
        val locallyInitiated = (streamId and 0x01uL) ==
            if (endpointRole == QuicEndpointRole.CLIENT) 0uL else 1uL
        return if (locallyInitiated) {
            parameters.initialMaxStreamDataBidiRemote
        } else {
            parameters.initialMaxStreamDataBidiLocal
        }
    }

    private fun receiveThreshold(window: Long): ULong =
        maxOf(1uL, window.toULong() / 2uL)
}

private const val APPLICATION_MESSAGE_OVERHEAD = 32
private const val QUIC_PACKET_OVERHEAD = 64
