package com.kerobit.kpeer.internal.nativeP2P

internal enum class SdpType {
    OFFER,
    ANSWER
}

internal data class NativeSdp(
    val type: SdpType,
    val description: String
)

internal data class NativeIceCandidate(
    val sdpMid: String?,
    val sdpMLineIndex: Int,
    val candidate: String
)
