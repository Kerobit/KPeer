package com.kerobit.kpeer

/**
 * ICE server for WebRTC (STUN/TURN).
 */
public data class IceServer(
    public val url: String,
    public val username: String? = null,
    public val credential: String? = null
)

/** Public configuration for KPeer. */
public data class KPeerConfig(
    public val initiator: Boolean,
    public val iceServers: List<IceServer> = defaultIceServers(),
    public val trickleIce: Boolean = true
) {
    public companion object {
        public fun defaultIceServers(): List<IceServer> = listOf(
            IceServer("stun:stun.l.google.com:19302"),
            IceServer("stun:stun1.l.google.com:19302"),
            IceServer("stun:stun2.l.google.com:19302"),
            IceServer("stun:stun3.l.google.com:19302")
        )
    }
}
