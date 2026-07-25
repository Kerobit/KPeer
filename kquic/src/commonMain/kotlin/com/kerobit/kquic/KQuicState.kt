package com.kerobit.kquic

enum class KQuicPeerConnectionState {
    NEW,
    GATHERING,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    DISCONNECTED,
    FAILED,
    CLOSED
}

enum class KQuicChannelState { CONNECTING, OPEN, CLOSING, CLOSED }

sealed class KQuicError(message: String) : IllegalStateException(message) {
    class InvalidSignal(message: String) : KQuicError(message)
    class InvalidChannel(message: String) : KQuicError(message)
    class NoViableCandidatePair : KQuicError("No direct or relay candidate pair is available")
    class UnsupportedDelivery(message: String) : KQuicError(message)
    class AuthenticationFailed(message: String) : KQuicError(message)
    class ProtocolViolation(message: String) : KQuicError(message)
    class TransportFailure(message: String) : KQuicError(message)
    class ConnectionTimedOut(timeoutMs: Long) : KQuicError(
        "QUIC connection did not complete within $timeoutMs ms"
    )
    class MessageTooLarge(size: Int, maximum: Int) : KQuicError(
        "Message size $size exceeds the configured maximum of $maximum bytes"
    )
    class TransportUnavailable : KQuicError(
        "No platform QUIC transport driver is available"
    )
}

fun interface KQuicSubscription {
    fun cancel()
}
