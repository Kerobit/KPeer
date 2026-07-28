package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError

internal enum class QuicEndpointRole {
    CLIENT,
    SERVER
}

internal data class QuicReceivedStreamData(val streamId: ULong, val bytes: ByteArray, val complete: Boolean)

/**
 * Owns stream-ID allocation, receive reassembly and stream-count/flow-control enforcement.
 */
internal class QuicStreamManager(
    private val role: QuicEndpointRole,
    private val localMaxBidirectionalStreams: ULong,
    private val localMaxUnidirectionalStreams: ULong,
    private val localMaxStreamData: ULong,
    private val maximumBufferedBytesPerStream: Int
) {
    private val receiveStreams = mutableMapOf<ULong, QuicOffsetReassembler>()
    private var peerMaxBidirectionalStreams: ULong = 0uL
    private var peerMaxUnidirectionalStreams: ULong = 0uL
    private var nextLocalBidirectional = if (role == QuicEndpointRole.CLIENT) 0uL else 1uL
    private var nextLocalUnidirectional = if (role == QuicEndpointRole.CLIENT) 2uL else 3uL

    fun updatePeerMaxStreams(bidirectional: Boolean, maximum: ULong) {
        if (maximum > (1uL shl 60)) {
            throw KQuicError.ProtocolViolation("MAX_STREAMS exceeds 2^60")
        }
        if (bidirectional) {
            peerMaxBidirectionalStreams = maxOf(peerMaxBidirectionalStreams, maximum)
        } else {
            peerMaxUnidirectionalStreams = maxOf(peerMaxUnidirectionalStreams, maximum)
        }
    }

    fun openBidirectional(): ULong {
        val streamNumber = nextLocalBidirectional / 4uL
        if (streamNumber >= peerMaxBidirectionalStreams) {
            throw KQuicError.ProtocolViolation("Peer bidirectional stream limit reached")
        }
        return nextLocalBidirectional.also { nextLocalBidirectional += 4uL }
    }

    fun openUnidirectional(): ULong {
        val streamNumber = nextLocalUnidirectional / 4uL
        if (streamNumber >= peerMaxUnidirectionalStreams) {
            throw KQuicError.ProtocolViolation("Peer unidirectional stream limit reached")
        }
        return nextLocalUnidirectional.also { nextLocalUnidirectional += 4uL }
    }

    fun receive(frame: QuicFrame.Stream): QuicReceivedStreamData {
        validatePeerCanSend(frame.streamId)
        val stream = receiveStreams.getOrPut(frame.streamId) {
            validateNewRemoteStream(frame.streamId)
            QuicOffsetReassembler(maxBufferedBytes = maximumBufferedBytesPerStream, maxOffset = localMaxStreamData)
        }
        val bytes = stream.add(frame.offset, frame.data, frame.fin)
        return QuicReceivedStreamData(frame.streamId, bytes, stream.complete)
    }

    fun reset(frame: QuicFrame.ResetStream) {
        validatePeerCanSend(frame.streamId)
        val stream = receiveStreams.getOrPut(frame.streamId) {
            validateNewRemoteStream(frame.streamId)
            QuicOffsetReassembler(maxBufferedBytes = maximumBufferedBytesPerStream, maxOffset = localMaxStreamData)
        }
        stream.add(frame.finalSize, ByteArray(0), fin = true)
    }

    fun updateLocalStreamLimit(streamId: ULong, maximumData: ULong) {
        receiveStreams[streamId]?.updateMaximumOffset(maximumData)
    }

    private fun validatePeerCanSend(streamId: ULong) {
        val locallyInitiated = (streamId and 0x01uL) == role.initiatorBit
        val unidirectional = (streamId and 0x02uL) != 0uL
        if (locallyInitiated && unidirectional) {
            throw KQuicError.ProtocolViolation("Peer sent STREAM data on a locally initiated unidirectional stream")
        }
    }

    private fun validateNewRemoteStream(streamId: ULong) {
        val locallyInitiated = (streamId and 0x01uL) == role.initiatorBit
        if (locallyInitiated) return
        val streamNumber = streamId / 4uL
        val unidirectional = (streamId and 0x02uL) != 0uL
        val limit = if (unidirectional) {
            localMaxUnidirectionalStreams
        } else {
            localMaxBidirectionalStreams
        }
        if (streamNumber >= limit) {
            throw KQuicError.ProtocolViolation("Peer exceeded the advertised stream limit")
        }
    }

    private val QuicEndpointRole.initiatorBit: ULong
        get() = if (this == QuicEndpointRole.CLIENT) 0uL else 1uL
}

/**
 * Send-side stream accounting. The connection actor queues returned frames for packetization.
 */
internal class QuicStreamSender(
    val streamId: ULong,
    initialMaximumData: ULong
) {
    private var maximumData = initialMaximumData
    private var nextOffset = 0uL
    private var finished = false

    fun updateMaximumData(value: ULong) {
        maximumData = maxOf(maximumData, value)
    }

    val availableData: ULong
        get() = maximumData - nextOffset

    val maximumDataLimit: ULong
        get() = maximumData

    fun frame(bytes: ByteArray, fin: Boolean = false): QuicFrame.Stream {
        if (finished) throw KQuicError.ProtocolViolation("STREAM data queued after FIN")
        val end = nextOffset.checkedAdd(bytes.size)
        if (end > maximumData) {
            throw KQuicError.ProtocolViolation("Peer stream flow-control limit reached")
        }
        return QuicFrame.Stream(streamId, nextOffset, fin, bytes.copyOf()).also {
            nextOffset = end
            if (fin) finished = true
        }
    }

    private fun ULong.checkedAdd(size: Int): ULong {
        val increment = size.toULong()
        if (this > ULong.MAX_VALUE - increment) {
            throw KQuicError.ProtocolViolation("STREAM offset overflow")
        }
        return this + increment
    }
}
