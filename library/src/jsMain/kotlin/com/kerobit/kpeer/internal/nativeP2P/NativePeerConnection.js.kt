package com.kerobit.kpeer.internal.nativeP2P

import com.kerobit.kpeer.KChannelConfig
import com.kerobit.kpeer.KPeerConnectionState
import com.kerobit.kpeer.KPeerContext
import com.kerobit.kpeer.KPeerIceCandidate
import com.kerobit.kpeer.KPeerSdpType
import com.kerobit.kpeer.KPeerStat
import com.kerobit.kpeer.KPeerStatValue
import com.kerobit.kpeer.KPeerStatsReport
import com.kerobit.kpeer.internal.TransportConfig
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlin.js.json

internal actual class NativePeerConnection actual constructor(
    config: TransportConfig,
    context: KPeerContext
) {
    private val iceCandidateBuffer = IceCandidateBuffer<RTCIceCandidateInit>()
    private val iceServers = config.iceServers.map { server ->
        json(
            "urls" to server.url,
            "username" to server.username,
            "credential" to server.credential
        ).unsafeCast<RTCIceServerInit>()
    }

    private val rtcConfig = json("iceServers" to iceServers.toTypedArray()).unsafeCast<RTCConfigurationInit>()

    private var peerConnection: RTCPeerConnection? = null

    private val localIceCandidatesChannel = Channel<KPeerIceCandidate>(Channel.UNLIMITED)
    actual val localIceCandidates: Flow<KPeerIceCandidate> = localIceCandidatesChannel.receiveAsFlow()

    private val _connectionState = MutableStateFlow(KPeerConnectionState.CONNECTING)
    actual val connectionState: Flow<KPeerConnectionState> = _connectionState.asStateFlow()

    actual val currentConnectionState: KPeerConnectionState
        get() = _connectionState.value

    private val _incomingDataChannels = MutableSharedFlow<NativeDataChannel>(extraBufferCapacity = 8)
    actual val incomingDataChannels: Flow<NativeDataChannel> = _incomingDataChannels.asSharedFlow()
    private val _negotiationNeeded = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    actual val negotiationNeeded: Flow<Unit> = _negotiationNeeded.asSharedFlow()

    private val _peerConnection: RTCPeerConnection
        get() = peerConnection ?: throw IllegalStateException("PeerConnection not started")

    actual fun startPeerConnection() {
        if (peerConnection != null) return

        val connection = RTCPeerConnection(rtcConfig)
        connection.onicecandidate = { event ->
            val c = event.candidate
            if (c != null && c != undefined) {
                localIceCandidatesChannel.trySend(
                    KPeerIceCandidate(
                        sdpMid = c.sdpMid,
                        sdpMLineIndex = c.sdpMLineIndex,
                        candidate = c.candidate
                    )
                )
            }
        }
        connection.oniceconnectionstatechange = {
            _connectionState.value = mapConnectionState(connection.asDynamic().iceConnectionState as String)
        }
        connection.ondatachannel = { event ->
            val ch = event.channel as RTCDataChannel
            _incomingDataChannels.tryEmit(NativeDataChannel(ch))
        }
        connection.onnegotiationneeded = {
            _negotiationNeeded.tryEmit(Unit)
        }
        peerConnection = connection
    }

    actual suspend fun createOffer(): String {
        val connection = _peerConnection
        val desc = connection.createOffer().await()
        connection.setLocalDescription(desc).await()
        return desc.sdp
    }

    actual suspend fun createAnswer(): String {
        val connection = _peerConnection
        val desc = connection.createAnswer().await()
        connection.setLocalDescription(desc).await()
        return desc.sdp
    }

    actual suspend fun setRemoteDescription(type: KPeerSdpType, sdp: String) {
        iceCandidateBuffer.reset()
        val rtcType = when (type) {
            KPeerSdpType.OFFER -> "offer"
            KPeerSdpType.ANSWER -> "answer"
        }
        val desc = js("({})").unsafeCast<RTCSessionDescriptionInit>().apply {
            this.type = rtcType
            this.sdp = sdp
        }
        val connection = _peerConnection
        connection.setRemoteDescription(desc).await()
        iceCandidateBuffer.markRemoteDescriptionSetAndFlush { candidate ->
            // Intentionally do not await: same as current behavior (fire-and-forget).
            connection.addIceCandidate(candidate)
        }
    }

    actual fun addIceCandidate(candidate: KPeerIceCandidate) {
        val init = js("({})").unsafeCast<RTCIceCandidateInit>().apply {
            this.candidate = candidate.candidate
            this.sdpMid = candidate.sdpMid
            this.sdpMLineIndex = candidate.sdpMLineIndex ?: 0
        }
        iceCandidateBuffer.queueOrAdd(init) { buffered ->
            _peerConnection.addIceCandidate(buffered)
        }
    }

    actual suspend fun getStats(): KPeerStatsReport {
        val report = _peerConnection.getStats().await()
        // RTCStatsReport is a Map-like object in browsers.
        val statsArray = js("Array.from(report.values())") as Array<dynamic>
        val stats = statsArray.mapNotNull { s ->
            val id = (s.id as? String) ?: return@mapNotNull null
            val type = (s.type as? String) ?: return@mapNotNull null
            val timestampUs = when (val t = s.timestamp) {
                is Number -> (t.toDouble() * 1000.0).toLong() // JS timestamp is ms
                else -> 0L
            }

            val keys = js("Object.keys(s)") as Array<String>
            val values = buildMap<String, KPeerStatValue> {
                for (k in keys) {
                    if (k == "id" || k == "type" || k == "timestamp") continue
                    val rawValue = s[k]
                    val normalized: Any? = if (rawValue == null || rawValue == undefined) {
                        null
                    } else {
                        rawValue
                    }
                    put(k, toStatValue(normalized))
                }
            }
            KPeerStat(id = id, type = type, timestampUs = timestampUs, values = values)
        }
        return KPeerStatsReport(stats = stats)
    }

    actual fun close() {
        peerConnection?.close()
        peerConnection = null
        localIceCandidatesChannel.close()
        _connectionState.value = KPeerConnectionState.DISCONNECTED
    }

    actual fun createDataChannel(config: KChannelConfig): NativeDataChannel? {
        val controlParams = config.toControlParams()
        val options = json(
            "ordered" to controlParams.ordered,
            "maxRetransmits" to (controlParams.maxRetransmitsOrNull ?: undefined)
        ).unsafeCast<RTCDataChannelInit>()
        val ch = _peerConnection.createDataChannel(config.label, options)
        config.bufferedAmountLowThreshold?.let { threshold ->
            ch.bufferedAmountLowThreshold = threshold.toInt()
        }
        return NativeDataChannel(ch)
    }

    private fun mapConnectionState(state: String): KPeerConnectionState = when (state) {
        "new", "checking" -> KPeerConnectionState.CONNECTING
        "connected", "completed" -> KPeerConnectionState.CONNECTED
        "disconnected" -> KPeerConnectionState.DISCONNECTED
        "failed" -> KPeerConnectionState.FAILED
        "closed" -> KPeerConnectionState.DISCONNECTED
        else -> KPeerConnectionState.DISCONNECTED
    }
}
