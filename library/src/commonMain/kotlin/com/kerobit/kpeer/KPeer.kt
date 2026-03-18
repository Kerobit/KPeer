package com.kerobit.kpeer

import com.kerobit.kpeer.internal.KPeerConnection
import com.kerobit.kpeer.internal.KPeerTransportEvent
import com.kerobit.kpeer.internal.TransportConfig
import com.kerobit.kpeer.internal.nativeP2P.NativeIceCandidate
import com.kerobit.kpeer.internal.nativeP2P.NativeSdp
import com.kerobit.kpeer.internal.nativeP2P.SdpType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Simple-peer style API: signaling + data channels. No media, no protocol layer.
 */
public interface KPeer {
    public val connectionState: Flow<KPeerConnectionState>
    public val channels: Flow<KChannel>
    public val signals: Flow<KPeerSignal>

    public suspend fun createChannel(config: ChannelConfig): KChannel?
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
        remotePeerId = "remote",
        logger = logger
    )

    private val _signals = MutableSharedFlow<KPeerSignal>(extraBufferCapacity = 64)
    override val signals: Flow<KPeerSignal> = _signals.asSharedFlow()
    private val _channels = MutableSharedFlow<KChannel>(extraBufferCapacity = 16)
    override val channels: Flow<KChannel> = _channels.asSharedFlow()

    override val connectionState: Flow<KPeerConnectionState> = connection.connectionState

    private var signalingJob: Job? = null
    private var offerJob: Job? = null
    private var started = false
    private var pendingOffer = false
    private val channelsByLabel = linkedMapOf<String, KChannelImpl>()
    private val emittedChannels = mutableSetOf<String>()

    init {
        context.scope.launch {
            connection.events.collect { event ->
                when (event) {
                    is KPeerTransportEvent.Connected -> {}
                    is KPeerTransportEvent.Disconnected, is KPeerTransportEvent.Failed -> {}
                    is KPeerTransportEvent.DataChannelAvailable -> emitChannel(event.label)
                    else -> {}
                }
            }
        }
        context.scope.launch {
            connection.negotiationNeeded.collect {
                if (config.initiator) {
                    requestOffer()
                }
            }
        }
        if (config.initiator) {
            startAsInitiator()
        }
    }

    private fun ensureStarted() {
        if (started) return
        started = true
        connection.startConnect()
        if (signalingJob == null) {
            signalingJob = context.scope.launch {
                connection.localIceCandidates.collect { c ->
                    _signals.tryEmit(
                        KPeerSignal.IceCandidate(
                            candidate = c.candidate,
                            sdpMid = c.sdpMid,
                            sdpMLineIndex = c.sdpMLineIndex
                        )
                    )
                }
            }
        }
    }

    private fun startAsInitiator() {
        ensureStarted()
        requestOffer()
    }

    private fun requestOffer() {
        if (!config.initiator) return
        ensureStarted()
        if (offerJob?.isActive == true) {
            pendingOffer = true
            return
        }
        offerJob = context.scope.launch {
            do {
                pendingOffer = false
                try {
                    val offer = connection.createOffer()
                    _signals.tryEmit(KPeerSignal.Offer(offer.description))
                } catch (e: Exception) {
                    logger.warn("Failed to create offer: ${e.message}")
                }
            } while (pendingOffer)
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
        if (this.config.initiator) {
            ensureStarted()
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

    override suspend fun signal(remote: KPeerSignal) {
        when (remote) {
            is KPeerSignal.Offer -> {
                if (!config.initiator && !started) {
                    ensureStarted()
                }
                connection.setRemoteDescription(NativeSdp(SdpType.OFFER, remote.sdp))
                if (!config.initiator) {
                    val answer = connection.createAnswer()
                    _signals.tryEmit(KPeerSignal.Answer(answer.description))
                }
            }
            is KPeerSignal.Answer -> {
                connection.setRemoteDescription(NativeSdp(SdpType.ANSWER, remote.sdp))
            }
            is KPeerSignal.IceCandidate -> {
                connection.addIceCandidate(
                    NativeIceCandidate(
                        sdpMid = remote.sdpMid,
                        sdpMLineIndex = remote.sdpMLineIndex ?: 0,
                        candidate = remote.candidate
                    )
                )
            }
        }
    }

    override fun close() {
        connection.close()
        signalingJob?.cancel()
        offerJob?.cancel()
    }
}
