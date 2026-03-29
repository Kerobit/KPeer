package com.kerobit.kpeer

/**
 * ICE server for WebRTC (STUN/TURN).
 */
data class IceServer(
    val url: String,
    val username: String? = null,
    val credential: String? = null
)

/** Public configuration for KPeer. */
data class KPeerConfig(
    val initiator: Boolean,
    val iceServers: List<IceServer> = defaultIceServers(),
    val signaling: SignalingConfig = SignalingConfig(),
    /**
     * If the peer does not reach `CONNECTED` within this time, it transitions to `FAILED`.
     * Set to `null` to disable.
     */
    val connectionTimeoutMs: Long? = null
) {
    companion object {
        fun defaultIceServers(): List<IceServer> = listOf(
            IceServer("stun:stun.l.google.com:19302"),
            IceServer("stun:stun1.l.google.com:19302"),
            IceServer("stun:stun2.l.google.com:19302"),
            IceServer("stun:stun3.l.google.com:19302")
        )
    }
}
