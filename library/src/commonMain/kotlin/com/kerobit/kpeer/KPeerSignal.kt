package com.kerobit.kpeer

/**
 * Signaling data to exchange with the remote peer (offer, answer, or ICE candidate).
 */
public sealed interface KPeerSignal {
    public data class Offer(val sdp: String) : KPeerSignal
    public data class Answer(val sdp: String) : KPeerSignal
    public data class IceCandidate(
        val candidate: String,
        val sdpMid: String?,
        val sdpMLineIndex: Int?
    ) : KPeerSignal
}
