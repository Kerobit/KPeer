package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicChannelConfig
import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicDelivery
import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicTransportEvent
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Deterministic channel, stream and flow-control state for one QUIC connection.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class QuicChannelManager(
    private val endpointRole: QuicEndpointRole,
    private val config: KQuicConfig,
    private val queuedFrameBytes: () -> Int,
    private val enqueue: (QuicFrame, Boolean) -> Boolean,
    private val bufferedAmountDrained: (String, Int) -> Unit,
    private val emitEvent: suspend (KQuicTransportEvent) -> Unit
) {
    private class Binding(
        val id: ULong,
        val label: String,
        val delivery: KQuicDelivery,
        val streamId: ULong
    )

    private data class PendingWrite(
        val binding: Binding,
        val bytes: ByteArray,
        val fin: Boolean,
        val reservedBytes: Int,
        val bufferedBytes: Int,
        var position: Int = 0
    )

    private val streamManager = QuicStreamManager(
        role = endpointRole,
        localMaxBidirectionalStreams = config.limits.maxBidirectionalStreams.toULong(),
        localMaxUnidirectionalStreams = config.limits.maxUnidirectionalStreams.toULong(),
        localMaxStreamData = config.limits.maxStreamData.toULong(),
        maximumBufferedBytesPerStream = config.limits.maxBufferedStreamBytes
    )
    private val streamSenders = mutableMapOf<ULong, QuicStreamSender>()
    private val streamDecoders = mutableMapOf<ULong, QuicChannelFrameDecoder>()
    private val pendingWrites = mutableListOf<PendingWrite>()
    private val streamDataConsumed = mutableMapOf<ULong, ULong>()
    private val channelsById = mutableMapOf<ULong, Binding>()
    private val channelsByLabel = mutableMapOf<String, Binding>()
    private val pendingApplicationBytes = AtomicInt(0)

    private var nextChannelId = if (endpointRole == QuicEndpointRole.CLIENT) 0uL else 1uL
    private var localControlStreamId: ULong? = null
    private var peerMaxConnectionData = 0uL
    private var peerConnectionDataSent = 0uL
    private var localConnectionDataConsumed = 0uL
    private var remoteParameters: QuicTransportParameterValues? = null

    var datagramsDropped: Long = 0
        private set
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
        streamManager.updatePeerMaxStreams(true, parameters.initialMaxStreamsBidi)
        streamManager.updatePeerMaxStreams(false, parameters.initialMaxStreamsUni)
    }

    fun updatePeerMaxStreams(bidirectional: Boolean, maximum: ULong) {
        streamManager.updatePeerMaxStreams(bidirectional, maximum)
    }

    fun updatePeerMaxStreamData(streamId: ULong, maximum: ULong) {
        streamSenders[streamId]?.let {
            it.updateMaximumData(maximum)
            drain()
        }
    }

    fun updatePeerMaxData(maximum: ULong) {
        peerMaxConnectionData = maxOf(peerMaxConnectionData, maximum)
        drain()
    }

    fun open(channelConfig: KQuicChannelConfig) {
        channelsByLabel[channelConfig.label]?.let { existing ->
            if (existing.delivery != channelConfig.delivery) {
                throw KQuicError.InvalidChannel(
                    "Channel ${channelConfig.label} already uses ${existing.delivery}"
                )
            }
            return
        }
        if (channelConfig.delivery == KQuicDelivery.UNRELIABLE &&
            remoteParameters?.maxDatagramFrameSize == null
        ) {
            throw KQuicError.UnsupportedDelivery(
                "The remote peer did not negotiate QUIC DATAGRAM"
            )
        }
        val streamId = if (channelConfig.delivery == KQuicDelivery.UNRELIABLE) {
            localControlStreamId ?: streamManager.openBidirectional().also {
                localControlStreamId = it
            }
        } else {
            streamManager.openBidirectional()
        }
        val binding = Binding(
            id = nextChannelId.also { nextChannelId += 2uL },
            label = channelConfig.label,
            delivery = channelConfig.delivery,
            streamId = streamId
        )
        channelsById[binding.id] = binding
        channelsByLabel[binding.label] = binding
        queue(binding, QuicChannelMessage.Open(binding.id, binding.label, binding.delivery))
    }

    fun send(label: String, payload: ByteArray, text: Boolean, reservedBytes: Int) {
        val binding = channelsByLabel[label]
            ?: throw KQuicError.InvalidChannel("Unknown channel $label")
        val message = if (text) {
            QuicChannelMessage.Text(binding.id, payload.decodeToString())
        } else {
            QuicChannelMessage.Bytes(binding.id, payload)
        }
        if (binding.delivery == KQuicDelivery.RELIABLE_ORDERED) {
            queue(
                binding,
                message,
                reservedBytes = reservedBytes,
                bufferedBytes = payload.size
            )
            return
        }
        val encoded = QuicChannelFrames.encode(message, config.limits)
        val remoteMaximum = remoteParameters?.maxDatagramFrameSize?.toInt()
            ?: throw KQuicError.UnsupportedDelivery("QUIC DATAGRAM is unavailable")
        if (encoded.size > minOf(remoteMaximum, QUIC_MAX_FRAME_PAYLOAD)) {
            datagramsDropped++
            release(reservedBytes)
            bufferedAmountDrained(label, payload.size)
            return
        }
        if (!enqueue(QuicFrame.Datagram(encoded), false)) datagramsDropped++
        release(reservedBytes)
        bufferedAmountDrained(label, payload.size)
    }

    fun close(label: String) {
        val binding = channelsByLabel.remove(label) ?: return
        channelsById.remove(binding.id)
        queue(
            binding,
            QuicChannelMessage.Close(binding.id),
            fin = binding.delivery == KQuicDelivery.RELIABLE_ORDERED
        )
    }

    fun peerBlockedOnConnection() {
        enqueue(
            QuicFrame.MaxData(
                config.limits.maxConnectionData.toULong() + localConnectionDataConsumed
            ),
            false
        )
    }

    fun peerBlockedOnStream(streamId: ULong) {
        enqueue(
            QuicFrame.MaxStreamData(
                streamId,
                config.limits.maxStreamData.toULong() + localConnectionDataConsumed
            ),
            false
        )
    }

    fun peerBlockedOnStreams() {
        streamsBlocked++
    }

    suspend fun streamReset(streamId: ULong) {
        channelsByLabel.values.firstOrNull { it.streamId == streamId }?.let {
            emitEvent(KQuicTransportEvent.ChannelClosed(it.label))
        }
    }

    suspend fun processStream(frame: QuicFrame.Stream) {
        val received = streamManager.receive(frame)
        if (received.bytes.isEmpty()) return
        localConnectionDataConsumed += received.bytes.size.toULong()
        val consumedOnStream = (streamDataConsumed[frame.streamId] ?: 0uL) +
            received.bytes.size.toULong()
        streamDataConsumed[frame.streamId] = consumedOnStream
        val decoder = streamDecoders.getOrPut(frame.streamId) {
            QuicChannelFrameDecoder(config.limits)
        }
        decoder.feed(received.bytes).forEach { processMessage(frame.streamId, it) }
        enqueue(
            QuicFrame.MaxData(
                config.limits.maxConnectionData.toULong() + localConnectionDataConsumed
            ),
            false
        )
        enqueue(
            QuicFrame.MaxStreamData(
                frame.streamId,
                config.limits.maxStreamData.toULong() + consumedOnStream
            ),
            false
        )
        streamManager.updateLocalStreamLimit(
            frame.streamId,
            config.limits.maxStreamData.toULong() + consumedOnStream
        )
    }

    suspend fun processDatagram(frame: QuicFrame.Datagram) {
        val messages = QuicChannelFrameDecoder(config.limits).feed(frame.data)
        if (messages.size != 1 || messages.single() is QuicChannelMessage.Open) {
            throw KQuicError.ProtocolViolation("Invalid KQuic DATAGRAM payload")
        }
        processMessage(NO_STREAM_ID, messages.single())
    }

    fun drain() {
        var index = 0
        while (index < pendingWrites.size) {
            val pending = pendingWrites[index]
            if (pendingWrites.take(index).any {
                    it.binding.streamId == pending.binding.streamId
                }
            ) {
                index++
                continue
            }
            val sender = streamSenders.getOrPut(pending.binding.streamId) {
                QuicStreamSender(
                    pending.binding.streamId,
                    initialPeerStreamCredit(pending.binding.streamId)
                )
            }
            val remaining = pending.bytes.size - pending.position
            val connectionCredit = peerMaxConnectionData - peerConnectionDataSent
            val available = minOf(
                minOf(remaining.toULong(), sender.availableData),
                minOf(connectionCredit, QUIC_MAX_FRAME_PAYLOAD.toULong())
            ).toInt()
            val emptyFin = remaining == 0 && pending.fin
            if (available == 0 && !emptyFin) {
                streamsBlocked++
                if (connectionCredit == 0uL) {
                    enqueue(QuicFrame.DataBlocked(peerMaxConnectionData), false)
                }
                if (sender.availableData == 0uL) {
                    enqueue(
                        QuicFrame.StreamDataBlocked(
                            pending.binding.streamId,
                            sender.maximumDataLimit
                        ),
                        false
                    )
                }
                index++
                continue
            }
            if (queuedFrameBytes() + available + QUIC_PACKET_OVERHEAD * 2 >
                config.limits.maxSendQueueBytes
            ) {
                return
            }
            val end = pending.position + available
            val chunk = pending.bytes.copyOfRange(pending.position, end)
            val final = pending.fin && end == pending.bytes.size
            check(enqueue(sender.frame(chunk, final), true))
            peerConnectionDataSent += chunk.size.toULong()
            pending.position = end
            if (pending.position == pending.bytes.size) {
                pendingWrites.removeAt(index)
                release(pending.reservedBytes)
                if (pending.bufferedBytes > 0) {
                    bufferedAmountDrained(pending.binding.label, pending.bufferedBytes)
                }
            } else {
                index++
            }
        }
    }

    fun reset() {
        pendingApplicationBytes.store(0)
    }

    private fun queue(
        binding: Binding,
        message: QuicChannelMessage,
        fin: Boolean = false,
        reservedBytes: Int = 0,
        bufferedBytes: Int = 0
    ) {
        val encoded = QuicChannelFrames.encode(message, config.limits)
        pendingWrites += PendingWrite(binding, encoded, fin, reservedBytes, bufferedBytes)
        drain()
    }

    private suspend fun processMessage(streamId: ULong, message: QuicChannelMessage) {
        when (message) {
            is QuicChannelMessage.Open -> {
                val existing = channelsByLabel[message.label]
                if (existing != null) {
                    if (existing.delivery != message.delivery) {
                        throw KQuicError.ProtocolViolation(
                            "Channel ${message.label} reopened with another delivery mode"
                        )
                    }
                    channelsById[message.channelId] = existing
                    return
                }
                val binding = Binding(
                    message.channelId,
                    message.label,
                    message.delivery,
                    streamId
                )
                channelsById[binding.id] = binding
                channelsByLabel[binding.label] = binding
                emitEvent(
                    KQuicTransportEvent.IncomingChannel(
                        KQuicChannelConfig(binding.label, binding.delivery)
                    )
                )
            }
            is QuicChannelMessage.Bytes -> {
                val binding = bindingForPayload(message.channelId, streamId) ?: return
                emitEvent(KQuicTransportEvent.BytesReceived(binding.label, message.payload))
            }
            is QuicChannelMessage.Text -> {
                val binding = bindingForPayload(message.channelId, streamId) ?: return
                emitEvent(KQuicTransportEvent.TextReceived(binding.label, message.text))
            }
            is QuicChannelMessage.Close -> {
                val binding = channelsById.remove(message.channelId)
                    ?: missingBinding(streamId, "Close for unknown channel")
                    ?: return
                channelsByLabel.remove(binding.label)
                emitEvent(KQuicTransportEvent.ChannelClosed(binding.label))
            }
        }
    }

    private fun bindingForPayload(channelId: ULong, streamId: ULong): Binding? =
        channelsById[channelId]
            ?: missingBinding(streamId, "Payload for unknown channel")

    private fun missingBinding(streamId: ULong, message: String): Binding? {
        if (streamId != NO_STREAM_ID) throw KQuicError.ProtocolViolation(message)
        datagramsDropped++
        return null
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
}

private const val APPLICATION_MESSAGE_OVERHEAD = 32
private const val QUIC_PACKET_OVERHEAD = 64
private val NO_STREAM_ID = ULong.MAX_VALUE
