package com.kerobit.kpeer

/**
 * State of the WebRTC peer connection.
 */
public enum class KPeerConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED
}
