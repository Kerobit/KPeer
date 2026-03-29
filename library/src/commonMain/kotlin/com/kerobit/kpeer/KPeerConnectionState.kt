package com.kerobit.kpeer

/**
 * State of the WebRTC peer connection.
 */
enum class KPeerConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    FAILED
}
