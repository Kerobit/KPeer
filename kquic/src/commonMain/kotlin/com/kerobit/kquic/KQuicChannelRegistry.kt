package com.kerobit.kquic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Owns public channel wrappers and translates channel events to their per-channel state.
 * Connection establishment deliberately remains outside this class.
 */
internal class KQuicChannelRegistry(
    private val context: KQuicContext,
    private val config: KQuicConfig,
    private val transport: KQuicTransport,
    private val connectionState: () -> KQuicPeerConnectionState,
    private val fail: (KQuicError) -> Unit
) {
    private val mutableChannels = MutableSharedFlow<KQuicChannel>(extraBufferCapacity = 16)
    private val channelsByLabel = linkedMapOf<String, KQuicChannel>()
    private val emittedLabels = mutableSetOf<String>()
    private val openedLabels = mutableSetOf<String>()

    val channels: Flow<KQuicChannel> = mutableChannels.asSharedFlow()

    fun create(channelConfig: KQuicChannelConfig): KQuicChannel {
        validate(channelConfig)
        channelsByLabel[channelConfig.label]?.let { existing ->
            if (existing.delivery != channelConfig.delivery) {
                throw KQuicError.InvalidChannel(
                    "Channel ${channelConfig.label} already exists with delivery ${existing.delivery}"
                )
            }
            return existing
        }
        return createWrapper(channelConfig).also { channel ->
            channelsByLabel[channel.label] = channel
            emit(channel)
            if (connectionState() == KQuicPeerConnectionState.CONNECTED) {
                transport.openChannel(channelConfig)
                openedLabels += channel.label
                channel.markOpen()
            }
        }
    }

    fun openPending() {
        channelsByLabel.values
            .filter { it.currentState == KQuicChannelState.CONNECTING }
            .forEach { channel ->
                transport.openChannel(KQuicChannelConfig(channel.label, channel.delivery))
                openedLabels += channel.label
                channel.markOpen()
            }
    }

    fun closeAll() {
        channelsByLabel.values.forEach(KQuicChannel::markClosed)
    }

    fun handle(event: KQuicTransportEvent): Boolean = when (event) {
        is KQuicTransportEvent.IncomingChannel -> {
            val existing = channelsByLabel[event.config.label]
            if (existing != null && existing.delivery != event.config.delivery) {
                fail(
                    KQuicError.ProtocolViolation(
                        "Channel ${event.config.label} was reopened with a different delivery mode"
                    )
                )
            } else {
                val channel = existing ?: createWrapper(event.config).also {
                    channelsByLabel[it.label] = it
                }
                openedLabels += channel.label
                channel.markOpen()
                emit(channel)
            }
            true
        }
        is KQuicTransportEvent.BytesReceived -> {
            channelsByLabel[event.label]?.emitBytes(event.payload)
                ?: fail(KQuicError.ProtocolViolation("Payload for unknown channel ${event.label}"))
            true
        }
        is KQuicTransportEvent.TextReceived -> {
            channelsByLabel[event.label]?.emitText(event.text)
                ?: fail(KQuicError.ProtocolViolation("Payload for unknown channel ${event.label}"))
            true
        }
        is KQuicTransportEvent.BufferedAmountDrained -> {
            channelsByLabel[event.label]?.drainBuffered(event.bytes)
            true
        }
        is KQuicTransportEvent.ChannelClosed -> {
            openedLabels.remove(event.label)
            channelsByLabel[event.label]?.markClosed()
            true
        }
        else -> false
    }

    private fun createWrapper(channelConfig: KQuicChannelConfig) = KQuicChannel(
        context = context,
        label = channelConfig.label,
        delivery = channelConfig.delivery,
        send = ::send,
        closeChannel = ::close
    )

    private fun send(channel: KQuicChannel, bytes: ByteArray, text: Boolean): Boolean {
        if (connectionState() != KQuicPeerConnectionState.CONNECTED) {
            throw KQuicError.TransportUnavailable()
        }
        val maximum = if (channel.delivery == KQuicDelivery.UNRELIABLE) {
            config.limits.maxDatagramSize
        } else {
            config.limits.maxMessageSize
        }
        if (bytes.size > maximum) throw KQuicError.MessageTooLarge(bytes.size, maximum)
        channel.addBuffered(bytes.size)
        return try {
            transport.send(channel.label, bytes, text).also { accepted ->
                if (!accepted) channel.drainBuffered(bytes.size)
            }
        } catch (error: Throwable) {
            channel.drainBuffered(bytes.size)
            throw error
        }
    }

    private fun close(channel: KQuicChannel) {
        if (openedLabels.remove(channel.label)) transport.closeChannel(channel.label)
        channel.markClosed()
    }

    private fun emit(channel: KQuicChannel) {
        if (emittedLabels.add(channel.label)) mutableChannels.tryEmit(channel)
    }

    private fun validate(channelConfig: KQuicChannelConfig) {
        val labelBytes = channelConfig.label.encodeToByteArray().size
        if (labelBytes > config.limits.maxChannelLabelBytes) {
            throw KQuicError.InvalidChannel(
                "Channel label uses $labelBytes UTF-8 bytes; " +
                    "maximum is ${config.limits.maxChannelLabelBytes}"
            )
        }
    }
}
