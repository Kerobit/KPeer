package com.kerobit.kpeer

/**
 * Signaling data to exchange with the remote peer (offer, answer, or ICE candidate).
 */
enum class KPeerSdpType {
    OFFER,
    ANSWER
}

interface KPeerSignal

data class KPeerOffer(
    val sdp: String,
    val type: KPeerSdpType = KPeerSdpType.OFFER
) : KPeerSignal

data class KPeerAnswer(
    val sdp: String,
    val type: KPeerSdpType = KPeerSdpType.ANSWER
) : KPeerSignal

data class KPeerIceCandidate(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int?
) : KPeerSignal
