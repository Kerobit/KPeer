package com.kerobit.kquic

import kotlinx.coroutines.flow.Flow

/**
 * KPeer-style public facade for one peer-to-peer QUIC connection.
 *
 * Channels and subscriptions can be prepared before [start]. No identity, candidate gathering or
 * socket I/O begins until [start] is called, except that applying a remote signal lazily starts a
 * responder for parity with KPeer's answering side.
 */
@ExperimentalKQuicApi
class KQuicPeer(
    private val context: KQuicContext,
    config: KQuicConfig,
    logger: KQuicLogger = NoOpKQuicLogger
) {
    private val connection = KQuicConnection(context, config, logger)

    val signals: Flow<KQuicSignal> get() = connection.signals
    val channels: Flow<KQuicChannel> get() = connection.channels
    val connectionState: Flow<KQuicPeerConnectionState> get() = connection.connectionState
    val currentConnectionState: KQuicPeerConnectionState
        get() = connection.currentConnectionState

    fun start() = connection.start()

    suspend fun signal(remote: KQuicSignal) = connection.signal(remote)

    fun createChannel(config: KQuicChannelConfig): KQuicChannel =
        connection.createChannel(config)

    suspend fun getStats(): KQuicStats = connection.getStats()

    fun onSignal(handler: (KQuicSignal) -> Unit): KQuicSubscription =
        connection.onSignal(handler)

    fun onChannel(handler: (KQuicChannel) -> Unit): KQuicSubscription =
        connection.onChannel(handler)

    fun onConnectionState(
        handler: (KQuicPeerConnectionState) -> Unit
    ): KQuicSubscription = connection.onConnectionState(handler)

    fun close() = connection.close()

    fun dispose() {
        connection.close()
        context.dispose()
    }
}
