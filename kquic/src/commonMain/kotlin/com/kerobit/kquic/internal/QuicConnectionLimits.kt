package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError

/** Enforces the server-side three-times anti-amplification limit. */
internal class QuicAmplificationGuard(
    private val server: Boolean
) {
    var bytesReceived: Long = 0
        private set
    var bytesSent: Long = 0
        private set
    var addressValidated: Boolean = !server
        private set

    fun onDatagramReceived(bytes: Int) {
        require(bytes >= 0)
        bytesReceived = checkedAdd(bytesReceived, bytes.toLong())
    }

    fun canSend(bytes: Int): Boolean {
        require(bytes >= 0)
        if (addressValidated) return true
        return bytesSent + bytes <= bytesReceived * 3
    }

    fun onDatagramSent(bytes: Int) {
        if (!canSend(bytes)) {
            throw KQuicError.ProtocolViolation("QUIC anti-amplification limit exceeded")
        }
        bytesSent = checkedAdd(bytesSent, bytes.toLong())
    }

    fun validateAddress() {
        addressValidated = true
    }

    private fun checkedAdd(current: Long, increment: Long): Long {
        if (current > Long.MAX_VALUE - increment) {
            throw KQuicError.ProtocolViolation("QUIC byte counter overflow")
        }
        return current + increment
    }
}

internal object QuicInitialDatagrams {
    const val MINIMUM_SIZE = 1_200

    fun pad(datagram: ByteArray): ByteArray =
        if (datagram.size >= MINIMUM_SIZE) datagram else datagram.copyOf(MINIMUM_SIZE)

    fun requireMinimumSize(datagram: ByteArray) {
        if (datagram.size < MINIMUM_SIZE) {
            throw KQuicError.ProtocolViolation("Initial UDP datagram is smaller than 1200 bytes")
        }
    }
}

/** Tracks connection IDs and applies active_connection_id_limit. */
internal class QuicConnectionIdRegistry(
    initialConnectionId: ByteArray,
    private val activeConnectionIdLimit: ULong
) {
    private data class Entry(val connectionId: ByteArray, val resetToken: ByteArray?)
    private val peerIds = mutableMapOf(0uL to Entry(initialConnectionId.copyOf(), null))

    init {
        require(initialConnectionId.size <= 20)
        require(activeConnectionIdLimit >= 2uL)
    }

    fun add(frame: QuicFrame.NewConnectionId): List<ULong> {
        val existing = peerIds[frame.sequence]
        if (existing != null &&
            (!existing.connectionId.contentEquals(frame.connectionId) ||
                existing.resetToken?.contentEquals(frame.statelessResetToken) == false)
        ) {
            throw KQuicError.ProtocolViolation("Connection ID sequence was reused with new values")
        }
        val retired = peerIds.keys.filter { it < frame.retirePriorTo }
        retired.forEach(peerIds::remove)
        if (frame.sequence !in peerIds) {
            peerIds[frame.sequence] = Entry(frame.connectionId.copyOf(), frame.statelessResetToken.copyOf())
        }
        if (peerIds.size.toULong() > activeConnectionIdLimit) {
            throw KQuicError.ProtocolViolation("Peer exceeded active_connection_id_limit")
        }
        return retired
    }

    fun retire(sequence: ULong) {
        if (peerIds.remove(sequence) == null) {
            throw KQuicError.ProtocolViolation("Unknown connection ID sequence retired")
        }
    }

    fun connectionId(sequence: ULong): ByteArray? = peerIds[sequence]?.connectionId?.copyOf()
}
