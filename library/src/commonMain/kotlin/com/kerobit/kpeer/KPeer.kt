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
class KPeer(
    private val context: KPeerContext,
    private val config: KPeerConfig,
    private val logger: KPeerLogger = NoOpKPeerLogger
) {
    private val transportConfig = TransportConfig(
        iceServers = config.iceServers
    )

    private val connection = KPeerConnection(
        context = context,
        config = transportConfig,
        logger = logger,
        connectionTimeoutMs = config.connectionTimeoutMs
    )

    private val _signals = MutableSharedFlow<KPeerSignal>(extraBufferCapacity = 64)
    val signals: Flow<KPeerSignal> = _signals.asSharedFlow()
    private val _channels = MutableSharedFlow<KChannel>(extraBufferCapacity = 16)
    /** Emits each channel once when it becomes visible to this peer. */
    val channels: Flow<KChannel> = _channels.asSharedFlow()
    private val signaler = KSignaler(
        context = context,
        config = config,
        connection = connection,
        logger = logger,
        signalsSink = _signals
    )

    val connectionState: Flow<KPeerConnectionState> = connection.connectionState

    /** Returns a typed snapshot of RTCPeerConnection stats (platform-dependent keys in values). */
    suspend fun getStats(): KPeerStatsReport = connection.getStats()

    private val channelsByLabel = linkedMapOf<String, KChannel>()
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

    private fun getOrCreateChannel(label: String): KChannel {
        return channelsByLabel.getOrPut(label) {
            KChannel(
                context = context,
                label = label,
                connection = connection
            )
        }
    }

    private fun emitChannel(label: String) {
        val channel = getOrCreateChannel(label)
        if (emittedChannels.add(label)) {
            _channels.tryEmit(channel)
        }
    }

    /** Creates or returns a data channel attached to the current peer connection. */
    suspend fun createChannel(config: KChannelConfig): KChannel? {
        // simple-peer semantics: only the initiator creates data channels.
        // The answering side must wait for the remote-created channel via ondatachannel/onChannel.
        if (!this.config.initiator) {
            // If the channel already exists (remote side created it), return it.
            connection.getDataChannel(config.label)?.let {
                val existing = getOrCreateChannel(it.label)
                emitChannel(it.label)
                return existing
            }
            logger.warn("createChannel(label=${config.label}) ignored on non-initiator; wait for onChannel instead.")
            return null
        }

        signaler.ensureStarted()
        val created = connection.createDataChannel(config) ?: return null
        val channel = getOrCreateChannel(created.label)
        emitChannel(created.label)
        return channel
    }

    /** Applies a signaling message received from the remote peer. */
    suspend fun signal(remote: KPeerSignal) = signaler.handleSignal(remote)

    /** Registers a callback for outgoing signaling messages. */
    fun onSignal(handler: (KPeerSignal) -> Unit): KSubscription {
        val job = context.scope.launch {
            signals.collect(handler)
        }
        return KSubscription { job.cancel() }
    }

    /** Registers a callback for discovered channels. */
    fun onChannel(handler: (KChannel) -> Unit): KSubscription {
        val job = context.scope.launch {
            channels.collect(handler)
        }
        return KSubscription { job.cancel() }
    }

    /** Registers a callback for peer connection state changes. */
    fun onConnectionState(handler: (KPeerConnectionState) -> Unit): KSubscription {
        val job = context.scope.launch {
            connectionState.collect(handler)
        }
        return KSubscription { job.cancel() }
    }

    /** Closes the peer connection but does not dispose caller-owned resources. */
    fun close() {
        connection.close()
        signaler.close()
    }

    /** Closes the peer connection and disposes internal resources owned by KPeer. */
    fun dispose() {
        close()
        context.dispose()
    }
}
