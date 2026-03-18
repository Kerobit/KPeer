package com.kerobit.kpeer.internal.nativeP2P

import kotlin.js.Promise

/**
 * Browser WebRTC external declarations. Uses the native WebRTC API.
 */

external interface RTCIceServerInit {
    var urls: dynamic
    var username: String?
    var credential: String?
}

external interface RTCConfigurationInit {
    var iceServers: Array<RTCIceServerInit>?
}

external open class RTCSessionDescriptionInit(
    var type: String,
    var sdp: String
)

external open class RTCIceCandidateInit(
    var candidate: String,
    var sdpMid: String?,
    var sdpMLineIndex: Int?
)

external interface RTCDataChannelInit {
    var ordered: Boolean?
    var maxRetransmits: Int?
}

external class RTCPeerConnection(config: RTCConfigurationInit?) {
    fun createOffer(): Promise<RTCSessionDescriptionInit>
    fun createAnswer(): Promise<RTCSessionDescriptionInit>
    fun setLocalDescription(description: RTCSessionDescriptionInit): Promise<Unit>
    fun setRemoteDescription(description: RTCSessionDescriptionInit): Promise<Unit>
    fun addIceCandidate(candidate: RTCIceCandidateInit): Promise<Unit>
    fun getStats(): Promise<dynamic>
    fun createDataChannel(label: String, options: RTCDataChannelInit? = definedExternally): RTCDataChannel
    fun close()

    var onicecandidate: ((dynamic) -> Unit)?
    var oniceconnectionstatechange: (() -> Unit)?
    var ondatachannel: ((dynamic) -> Unit)?
    var onnegotiationneeded: (() -> Unit)?
}

external class RTCDataChannel {
    val label: String
    val readyState: String
    val bufferedAmount: Int
    var bufferedAmountLowThreshold: Int
    fun send(data: dynamic): Boolean
    fun close()

    var onmessage: ((dynamic) -> Unit)?
    var onopen: (() -> Unit)?
    var onclose: (() -> Unit)?
    var onclosing: (() -> Unit)?
    var onbufferedamountlow: (() -> Unit)?
}
