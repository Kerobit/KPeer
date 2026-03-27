package com.kerobit.kpeer

/**
 * Signaling data to exchange with the remote peer (offer, answer, or ICE candidate).
 */
public enum class KPeerSdpType {
    OFFER,
    ANSWER
}

public interface KPeerSignal

public data class KPeerOffer(
    val sdp: String,
    val type: KPeerSdpType = KPeerSdpType.OFFER
) : KPeerSignal

public data class KPeerAnswer(
    val sdp: String,
    val type: KPeerSdpType = KPeerSdpType.ANSWER
) : KPeerSignal

public data class KPeerIceCandidate(
    val candidate: String,
    val sdpMid: String?,
    val sdpMLineIndex: Int?
) : KPeerSignal
