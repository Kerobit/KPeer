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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

/**
 * Simple-peer style API: signaling + data channel. No media, no protocol layer.
 */
public interface KPeer {
    public val connectionState: Flow<KPeerConnectionState>
    public val data: Flow<ByteArray>
    public val signals: Flow<KPeerSignal>

    public suspend fun signal(remote: KPeerSignal)
    public fun send(data: ByteArray): Boolean
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
        iceServers = config.iceServers,
        controlChannelLabel = "data",
        dataChannelLabel = config.channelName,
        ordered = config.ordered,
        reliable = config.reliable
    )

    private val connection = KPeerConnection(
        context = context,
        config = transportConfig,
        remotePeerId = "remote",
        logger = logger
    )

    private val _signals = MutableSharedFlow<KPeerSignal>(extraBufferCapacity = 64)
    override val signals: Flow<KPeerSignal> = _signals.asSharedFlow()

    override val connectionState: Flow<KPeerConnectionState> = connection.connectionState

    override val data: Flow<ByteArray> = connection.events
        .filterIsInstance<KPeerTransportEvent.DataReceived>()
        .filter { it.label == transportConfig.dataChannelLabel }
        .map { it.data }
        .shareIn(context.scope, SharingStarted.WhileSubscribed(), replay = 0)

    private var signalingJob: Job? = null
    private var started = false

    init {
        context.scope.launch {
            connection.events.collect { event ->
                when (event) {
                    is KPeerTransportEvent.Connected -> {}
                    is KPeerTransportEvent.Disconnected, is KPeerTransportEvent.Failed -> {}
                    else -> {}
                }
            }
        }
        if (config.initiator) {
            startAsInitiator()
        }
    }

    private fun startAsInitiator() {
        if (started) return
        started = true
        connection.startConnect()
        context.scope.launch {
            try {
                val offer = connection.createOffer()
                _signals.tryEmit(KPeerSignal.Offer(offer.description))
            } catch (e: Exception) {
                logger.warn("Failed to create offer: ${e.message}")
            }
        }
        context.scope.launch {
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

    override suspend fun signal(remote: KPeerSignal) {
        when (remote) {
            is KPeerSignal.Offer -> {
                if (!config.initiator && !started) {
                    started = true
                    connection.startConnect()
                }
                connection.setRemoteDescription(NativeSdp(SdpType.OFFER, remote.sdp))
                if (!config.initiator) {
                    val answer = connection.createAnswer()
                    _signals.tryEmit(KPeerSignal.Answer(answer.description))
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

    override fun send(data: ByteArray): Boolean = connection.send(data)

    override fun close() {
        connection.close()
        signalingJob?.cancel()
    }
}
