package com.kerobit.kpeer

import com.kerobit.kpeer.internal.KPeerConnection
import com.kerobit.kpeer.internal.KPeerTransportEvent
import com.kerobit.kpeer.internal.TransportConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Simple-peer style API: signaling + data channels. No media, no protocol layer.
 */
public interface KPeer {
    public val connectionState: Flow<KPeerConnectionState>
    /** Emits each channel once when it becomes visible to this peer. */
    public val channels: Flow<KChannel>
    /** Outgoing signaling messages that must be delivered to the remote peer. */
    public val signals: Flow<KPeerSignal>

    /** Creates or returns a data channel attached to the current peer connection. */
    public suspend fun createChannel(config: ChannelConfig): KChannel?
    /** Applies a signaling message received from the remote peer. */
    public suspend fun signal(remote: KPeerSignal)
    public fun close()
}

/**
 * Creates a KPeer instance. Initiator creates offer and emits signals; non-initiator waits for signal(Offer) then answers.
 */
public fun KPeer(
    context: KPeerContext,
    config: KPeerConfig,
    logger: KPeerLogger = NoOpKPeerLogger
): KPeer = KPeerImpl(context, config, logger)

internal class KPeerImpl(
    private val context: KPeerContext,
    private val config: KPeerConfig,
    private val logger: KPeerLogger
) : KPeer {
    private val transportConfig = TransportConfig(
        iceServers = config.iceServers
    )

    private val connection = KPeerConnection(
        context = context,
        config = transportConfig,
        logger = logger
    )

    private val _signals = MutableSharedFlow<KPeerSignal>(extraBufferCapacity = 64)
    override val signals: Flow<KPeerSignal> = _signals.asSharedFlow()
    private val _channels = MutableSharedFlow<KChannel>(extraBufferCapacity = 16)
    override val channels: Flow<KChannel> = _channels.asSharedFlow()
    private val signaler = KSignaler(
        context = context,
        config = config,
        connection = connection,
        logger = logger,
        signalsSink = _signals
    )

    override val connectionState: Flow<KPeerConnectionState> = connection.connectionState

    private val channelsByLabel = linkedMapOf<String, KChannelImpl>()
    private val emittedChannels = mutableSetOf<String>()

    init {
        context.scope.launch {
            connection.events.collect { event ->
                when (event) {
                    is KPeerTransportEvent.Connected -> {}
                    is KPeerTransportEvent.Disconnected, is KPeerTransportEvent.Failed -> {}
                    // Incoming channels are discovered from transport events, while locally
                    // created channels are emitted directly from createChannel().
                    is KPeerTransportEvent.DataChannelAvailable -> emitChannel(event.label)
                    else -> {}
                }
            }
        }
    }

    private fun getOrCreateChannel(label: String): KChannelImpl {
        return channelsByLabel.getOrPut(label) {
            KChannelImpl(
                label = label,
                connection = connection,
                context = context
            )
        }
    }

    private fun emitChannel(label: String) {
        val channel = getOrCreateChannel(label)
        if (emittedChannels.add(label)) {
            _channels.tryEmit(channel)
        }
    }

    override suspend fun createChannel(config: ChannelConfig): KChannel? {
        // The initiator must have an active PeerConnection before creating the first channel.
        if (this.config.initiator) {
            signaler.ensureStarted()
        }
        val created = connection.createDataChannel(
            label = config.label,
            ordered = config.ordered,
            reliable = config.reliable
        ) ?: return null
        val channel = getOrCreateChannel(created.label)
        emitChannel(created.label)
        return channel
    }

    override suspend fun signal(remote: KPeerSignal) = signaler.handleSignal(remote)

    override fun close() {
        connection.close()
        signaler.close()
    }
}
