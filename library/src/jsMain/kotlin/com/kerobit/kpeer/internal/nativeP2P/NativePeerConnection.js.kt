package com.kerobit.kpeer.internal.nativeP2P

import com.kerobit.kpeer.KPeerConnectionState
import com.kerobit.kpeer.KPeerContext
import com.kerobit.kpeer.internal.TransportConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _localIceCandidates = MutableSharedFlow<NativeIceCandidate>(extraBufferCapacity = 64)
    actual val localIceCandidates: Flow<NativeIceCandidate> = _localIceCandidates.asSharedFlow()

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
            val c = event.asDynamic().candidate
            if (c != null && c != undefined) {
                _localIceCandidates.tryEmit(
                    NativeIceCandidate(
                        sdpMid = c.sdpMid,
                        sdpMLineIndex = c.sdpMLineIndex ?: 0,
                        candidate = c.candidate
                    )
                )
            }
        }
        peerConnection.oniceconnectionstatechange = {
            val pc = peerConnection.asDynamic()
            _connectionState.value = mapConnectionState(pc.iceConnectionState as String)
        }
        peerConnection.ondatachannel = { event ->
            val ch = event.asDynamic().channel as RTCDataChannel
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
        val desc = RTCSessionDescriptionInit(type = type, sdp = sdp.description)
        peerConnection.setRemoteDescription(desc).await()
    }

    actual fun addIceCandidate(candidate: NativeIceCandidate) {
        val init = RTCIceCandidateInit(
            candidate = candidate.candidate,
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex
        )
        peerConnection.addIceCandidate(init)
    }

    actual fun close() {
        peerConnection.close()
        _connectionState.value = KPeerConnectionState.DISCONNECTED
    }

    actual fun createDataChannel(label: String, ordered: Boolean, reliable: Boolean): NativeDataChannel? {
        val options = json(
            "ordered" to ordered,
            "maxRetransmits" to (if (reliable) undefined else 0)
        ).unsafeCast<RTCDataChannelInit>()
        val ch = peerConnection.createDataChannel(label, options)
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
