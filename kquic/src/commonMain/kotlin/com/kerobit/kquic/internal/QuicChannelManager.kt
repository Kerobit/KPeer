package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicChannelConfig
import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicDelivery
import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicTransportEvent

internal class QuicChannelManager(
    private val endpointRole: QuicEndpointRole,
    private val config: KQuicConfig,
    private val queuedFrameBytes: () -> Int,
    private val enqueue: (QuicFrame, Boolean) -> Boolean,
    private val bufferedAmountDrained: (String, Int) -> Unit,
    private val emitEvent: suspend (KQuicTransportEvent) -> Unit
) {
    private class Binding(val id: ULong, val label: String, val delivery: KQuicDelivery, val streamId: ULong)

    private val streamManager = QuicStreamManager(
        role = endpointRole,
        localMaxBidirectionalStreams = config.limits.maxBidirectionalStreams.toULong(),
        localMaxUnidirectionalStreams = config.limits.maxUnidirectionalStreams.toULong(),
        localMaxStreamData = config.limits.maxStreamData.toULong(),
        maximumBufferedBytesPerStream = config.limits.maxBufferedStreamBytes
    )
    private val streamDecoders = mutableMapOf<ULong, QuicChannelFrameDecoder>()
    private val channelsById = mutableMapOf<ULong, Binding>()
    private val channelsByLabel = mutableMapOf<String, Binding>()
    private val flowController = QuicFlowController(endpointRole, config, queuedFrameBytes, enqueue, bufferedAmountDrained)

    private var nextChannelId = if (endpointRole == QuicEndpointRole.CLIENT) 0uL else 1uL
    private var localControlStreamId: ULong? = null
    private var remoteParameters: QuicTransportParameterValues? = null

    var datagramsDropped: Long = 0
        private set
    val streamsBlocked: Long get() = flowController.streamsBlocked

    fun reserve(payloadSize: Int): Int? = flowController.reserve(payloadSize)

    fun release(reservedBytes: Int) = flowController.release(reservedBytes)

    fun applyRemoteParameters(parameters: QuicTransportParameterValues) {
        remoteParameters = parameters
        flowController.applyRemoteParameters(parameters)
        streamManager.updatePeerMaxStreams(true, parameters.initialMaxStreamsBidi)
        streamManager.updatePeerMaxStreams(false, parameters.initialMaxStreamsUni)
    }

    fun updatePeerMaxStreams(bidirectional: Boolean, maximum: ULong) {
        streamManager.updatePeerMaxStreams(bidirectional, maximum)
    }

    fun updatePeerMaxStreamData(streamId: ULong, maximum: ULong) {
        flowController.updatePeerMaxStreamData(streamId, maximum)
    }

    fun updatePeerMaxData(maximum: ULong) {
        flowController.updatePeerMaxData(maximum)
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
            throw KQuicError.UnsupportedDelivery("The remote peer did not negotiate QUIC DATAGRAM")
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
            queue(binding, message, reservedBytes = reservedBytes, bufferedBytes = payload.size)
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
        queue(binding, QuicChannelMessage.Close(binding.id), fin = binding.delivery == KQuicDelivery.RELIABLE_ORDERED)
    }

    fun peerBlockedOnConnection() {
        enqueue(flowController.connectionCreditFrame(), false)
    }

    fun peerBlockedOnStream(streamId: ULong) {
        enqueue(flowController.streamCreditFrame(streamId), false)
    }

    fun peerBlockedOnStreams() {
        flowController.peerBlockedOnStreams()
    }

    suspend fun streamReset(streamId: ULong) {
        channelsByLabel.values.firstOrNull { it.streamId == streamId }?.let {
            emitEvent(KQuicTransportEvent.ChannelClosed(it.label))
        }
    }

    suspend fun processStream(frame: QuicFrame.Stream) {
        val received = streamManager.receive(frame)
        if (received.bytes.isEmpty()) return
        val credit = flowController.consume(frame.streamId, received.bytes.size)
        val decoder = streamDecoders.getOrPut(frame.streamId) {
            QuicChannelFrameDecoder(config.limits)
        }
        decoder.feed(received.bytes).forEach { processMessage(frame.streamId, it) }
        credit.connectionMaximum?.let {
            enqueue(QuicFrame.MaxData(it), false)
        }
        credit.streamMaximum?.let {
            enqueue(QuicFrame.MaxStreamData(frame.streamId, it), false)
            streamManager.updateLocalStreamLimit(frame.streamId, it)
        }
    }

    suspend fun processDatagram(frame: QuicFrame.Datagram) {
        val messages = QuicChannelFrameDecoder(config.limits).feed(frame.data)
        if (messages.size != 1 || messages.single() is QuicChannelMessage.Open) {
            throw KQuicError.ProtocolViolation("Invalid KQuic DATAGRAM payload")
        }
        processMessage(NO_STREAM_ID, messages.single())
    }

    fun drain() = flowController.drain()

    fun reset() = flowController.reset()

    private fun queue(
        binding: Binding,
        message: QuicChannelMessage,
        fin: Boolean = false,
        reservedBytes: Int = 0,
        bufferedBytes: Int = 0
    ) {
        val encoded = QuicChannelFrames.encode(message, config.limits)
        flowController.queue(binding.streamId, binding.label, encoded, fin, reservedBytes, bufferedBytes)
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
                val binding = Binding(message.channelId, message.label, message.delivery, streamId)
                channelsById[binding.id] = binding
                channelsByLabel[binding.label] = binding
                emitEvent(KQuicTransportEvent.IncomingChannel(KQuicChannelConfig(binding.label, binding.delivery)))
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

}

private val NO_STREAM_ID = ULong.MAX_VALUE
