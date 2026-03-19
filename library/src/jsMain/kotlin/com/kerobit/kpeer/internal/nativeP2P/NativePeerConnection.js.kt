package com.kerobit.kpeer.internal.nativeP2P

import com.kerobit.kpeer.ChannelConfig
import com.kerobit.kpeer.KPeerConnectionState
import com.kerobit.kpeer.KPeerContext
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
import kotlinx.coroutines.channels.trySend
import kotlin.js.json

internal actual class NativePeerConnection actual constructor(
    config: TransportConfig,
    context: KPeerContext
) {
    private val iceServers = config.iceServers.map { server ->
        json(
            "urls" to server.url,
            "username" to server.username,
            "credential" to server.credential
        ).unsafeCast<RTCIceServerInit>()
    }

    private val rtcConfig = json("iceServers" to iceServers.toTypedArray()).unsafeCast<RTCConfigurationInit>()

    private val peerConnection = RTCPeerConnection(rtcConfig)

    private val localIceCandidatesChannel = Channel<NativeIceCandidate>(Channel.UNLIMITED)
    actual val localIceCandidates: Flow<NativeIceCandidate> = localIceCandidatesChannel.receiveAsFlow()

    private val _connectionState = MutableStateFlow(KPeerConnectionState.CONNECTING)
    actual val connectionState: Flow<KPeerConnectionState> = _connectionState.asStateFlow()

    actual val currentConnectionState: KPeerConnectionState
        get() = _connectionState.value

    private val _incomingDataChannels = MutableSharedFlow<NativeDataChannel>(extraBufferCapacity = 8)
    actual val incomingDataChannels: Flow<NativeDataChannel> = _incomingDataChannels.asSharedFlow()
    private val _negotiationNeeded = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    actual val negotiationNeeded: Flow<Unit> = _negotiationNeeded.asSharedFlow()

    init {
        peerConnection.onicecandidate = { event ->
            val c = event.candidate
            if (c != null && c != undefined) {
                localIceCandidatesChannel.trySend(
                    NativeIceCandidate(
                        sdpMid = c.sdpMid,
                        sdpMLineIndex = c.sdpMLineIndex ?: 0,
                        candidate = c.candidate
                    )
                )
            }
        }
        peerConnection.oniceconnectionstatechange = {
            _connectionState.value = mapConnectionState(peerConnection.asDynamic().iceConnectionState as String)
        }
        peerConnection.ondatachannel = { event ->
            val ch = event.channel as RTCDataChannel
            _incomingDataChannels.tryEmit(NativeDataChannel(ch))
        }
        peerConnection.onnegotiationneeded = {
            _negotiationNeeded.tryEmit(Unit)
        }
    }

    actual suspend fun createOffer(): NativeSdp {
        val desc = peerConnection.createOffer().await()
        peerConnection.setLocalDescription(desc).await()
        return NativeSdp(
            type = SdpType.OFFER,
            description = desc.sdp
        )
    }

    actual suspend fun createAnswer(): NativeSdp {
        val desc = peerConnection.createAnswer().await()
        peerConnection.setLocalDescription(desc).await()
        return NativeSdp(
            type = SdpType.ANSWER,
            description = desc.sdp
        )
    }

    actual suspend fun setRemoteDescription(sdp: NativeSdp) {
        val type = when (sdp.type) {
            SdpType.OFFER -> "offer"
            SdpType.ANSWER -> "answer"
        }
        val desc = js("({})").unsafeCast<RTCSessionDescriptionInit>().apply {
            this.type = type
            this.sdp = sdp.description
        }
        peerConnection.setRemoteDescription(desc).await()
    }

    actual fun addIceCandidate(candidate: NativeIceCandidate) {
        val init = js("({})").unsafeCast<RTCIceCandidateInit>().apply {
            this.candidate = candidate.candidate
            this.sdpMid = candidate.sdpMid
            this.sdpMLineIndex = candidate.sdpMLineIndex
        }
        peerConnection.addIceCandidate(init)
    }

    actual suspend fun getStats(): KPeerStatsReport {
        val report = peerConnection.getStats().await()
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
                    put(k, jsToStatValue(s[k]))
                }
            }
            KPeerStat(id = id, type = type, timestampUs = timestampUs, values = values)
        }
        return KPeerStatsReport(stats = stats)
    }

    actual fun close() {
        peerConnection.close()
        localIceCandidatesChannel.close()
        _connectionState.value = KPeerConnectionState.DISCONNECTED
    }

    actual fun createDataChannel(config: ChannelConfig): NativeDataChannel? {
        val options = json(
            "ordered" to config.ordered,
            "maxRetransmits" to (if (config.reliable) undefined else 0)
        ).unsafeCast<RTCDataChannelInit>()
        val ch = peerConnection.createDataChannel(config.label, options)
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

private fun jsToStatValue(v: dynamic): KPeerStatValue = when {
    v == null || v == undefined -> KPeerStatValue.Null
    jsTypeOf(v) == "string" -> KPeerStatValue.Str(v as String)
    jsTypeOf(v) == "number" -> KPeerStatValue.Num((v as Number).toDouble())
    jsTypeOf(v) == "boolean" -> KPeerStatValue.Bool(v as Boolean)
    else -> KPeerStatValue.Str((js("String(v)") as String))
}
