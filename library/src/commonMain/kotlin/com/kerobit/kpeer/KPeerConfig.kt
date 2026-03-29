package com.kerobit.kpeer

/**
 * Describes a single STUN or TURN server used during ICE gathering.
 *
 * Maps to WebRTC `RTCIceServer`-style entries: a URL plus optional TURN credentials.
 */
data class IceServer(
    /** ICE server URL, e.g. `stun:stun.example.com:3478` or `turn:turn.example.com:3478`. */
    val url: String,
    /** TURN username; ignored for STUN-only URLs. */
    val username: String? = null,
    /** TURN password or credential; ignored for STUN-only URLs. */
    val credential: String? = null
)

/**
 * Top-level settings for [KPeer]: role in the session, ICE servers, how local ICE candidates
 * are surfaced to your app, and optional connection timeout.
 */
data class KPeerConfig(
    /**
     * When `true`, this peer is expected to create the offer and may create outbound data channels.
     * When `false`, this peer answers the offer and typically receives data channels from the remote.
     * This matches common WebRTC “initiator vs answerer” semantics.
     */
    val initiator: Boolean,
    /**
     * STUN/TURN servers used for ICE candidate gathering on the underlying peer connection.
     * Defaults to [defaultIceServers] (public Google STUN) when you pass the default value.
     */
    val iceServers: List<IceServer> = defaultIceServers(),
    /**
     * Controls timing and batching when *local* ICE candidates are delivered to your code
     * (e.g. before you send them over your signaling layer). See [KIceCandidateEmitPolicy].
     */
    val iceCandidateEmitPolicy: KIceCandidateEmitPolicy = KIceCandidateEmitPolicy(),
    /**
     * Maximum time to wait for the connection to reach [KPeerConnectionState.CONNECTED], in milliseconds.
     * If the deadline passes, the peer moves to [KPeerConnectionState.FAILED] and the transport is torn down.
     * Use `null` to wait indefinitely.
     */
    val connectionTimeoutMs: Long? = null
) {
    companion object {
        /**
         * Reasonable default STUN-only servers for development and simple deployments.
         * Production apps often supply their own TURN servers for restrictive networks.
         */
        fun defaultIceServers(): List<IceServer> = listOf(
            IceServer("stun:stun.l.google.com:19302"),
            IceServer("stun:stun1.l.google.com:19302"),
            IceServer("stun:stun2.l.google.com:19302"),
            IceServer("stun:stun3.l.google.com:19302")
        )
    }
}
