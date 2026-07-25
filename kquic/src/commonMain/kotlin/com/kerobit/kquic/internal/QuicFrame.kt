package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError

internal sealed interface QuicFrame {
    data object Ping : QuicFrame
    data class Ack(val ranges: List<QuicAckRange>, val ackDelay: ULong) : QuicFrame
    data class ResetStream(
        val streamId: ULong,
        val applicationErrorCode: ULong,
        val finalSize: ULong
    ) : QuicFrame
    data class StopSending(val streamId: ULong, val applicationErrorCode: ULong) : QuicFrame
    data class Crypto(val offset: ULong, val data: ByteArray) : QuicFrame
    data class Stream(val streamId: ULong, val offset: ULong, val fin: Boolean, val data: ByteArray) : QuicFrame
    data class MaxData(val maximumData: ULong) : QuicFrame
    data class MaxStreamData(val streamId: ULong, val maximumStreamData: ULong) : QuicFrame
    data class MaxStreams(val bidirectional: Boolean, val maximumStreams: ULong) : QuicFrame
    data class DataBlocked(val maximumData: ULong) : QuicFrame
    data class StreamDataBlocked(val streamId: ULong, val maximumStreamData: ULong) : QuicFrame
    data class StreamsBlocked(val bidirectional: Boolean, val maximumStreams: ULong) : QuicFrame
    data class NewConnectionId(
        val sequence: ULong,
        val retirePriorTo: ULong,
        val connectionId: ByteArray,
        val statelessResetToken: ByteArray
    ) : QuicFrame
    data class RetireConnectionId(val sequence: ULong) : QuicFrame
    data class Datagram(val data: ByteArray) : QuicFrame
    data class ConnectionClose(val errorCode: ULong, val frameType: ULong, val reason: String) : QuicFrame
    data object HandshakeDone : QuicFrame
}

/** Inclusive packet-number range. QUIC ACK ranges are ordered newest to oldest. */
internal data class QuicAckRange(val start: ULong, val end: ULong) {
    init { require(start <= end) }
}

internal object QuicFrames {
    private const val PADDING = 0x00uL
    private const val PING = 0x01uL
    private const val ACK = 0x02uL
    private const val RESET_STREAM = 0x04uL
    private const val STOP_SENDING = 0x05uL
    private const val CRYPTO = 0x06uL
    private const val STREAM_BASE = 0x08uL
    private const val MAX_DATA = 0x10uL
    private const val MAX_STREAM_DATA = 0x11uL
    private const val MAX_STREAMS_BIDI = 0x12uL
    private const val MAX_STREAMS_UNI = 0x13uL
    private const val DATA_BLOCKED = 0x14uL
    private const val STREAM_DATA_BLOCKED = 0x15uL
    private const val STREAMS_BLOCKED_BIDI = 0x16uL
    private const val STREAMS_BLOCKED_UNI = 0x17uL
    private const val NEW_CONNECTION_ID = 0x18uL
    private const val RETIRE_CONNECTION_ID = 0x19uL
    private const val CONNECTION_CLOSE_TRANSPORT = 0x1cuL
    private const val HANDSHAKE_DONE = 0x1euL
    private const val DATAGRAM_WITHOUT_LENGTH = 0x30uL
    private const val DATAGRAM_WITH_LENGTH = 0x31uL

    fun encode(frame: QuicFrame): ByteArray = ByteWriter().apply {
        when (frame) {
            QuicFrame.Ping -> varInt(PING)
            is QuicFrame.Ack -> encodeAck(frame)
            is QuicFrame.ResetStream -> {
                varInt(RESET_STREAM)
                varInt(frame.streamId)
                varInt(frame.applicationErrorCode)
                varInt(frame.finalSize)
            }
            is QuicFrame.StopSending -> {
                varInt(STOP_SENDING)
                varInt(frame.streamId)
                varInt(frame.applicationErrorCode)
            }
            is QuicFrame.Crypto -> {
                varInt(CRYPTO)
                varInt(frame.offset)
                varInt(frame.data.size.toULong())
                bytes(frame.data)
            }
            is QuicFrame.Stream -> {
                varInt(STREAM_BASE or 0x06uL or if (frame.fin) 0x01uL else 0uL)
                varInt(frame.streamId)
                varInt(frame.offset)
                varInt(frame.data.size.toULong())
                bytes(frame.data)
            }
            is QuicFrame.MaxData -> {
                varInt(MAX_DATA)
                varInt(frame.maximumData)
            }
            is QuicFrame.MaxStreamData -> {
                varInt(MAX_STREAM_DATA)
                varInt(frame.streamId)
                varInt(frame.maximumStreamData)
            }
            is QuicFrame.MaxStreams -> {
                varInt(if (frame.bidirectional) MAX_STREAMS_BIDI else MAX_STREAMS_UNI)
                varInt(frame.maximumStreams)
            }
            is QuicFrame.DataBlocked -> {
                varInt(DATA_BLOCKED)
                varInt(frame.maximumData)
            }
            is QuicFrame.StreamDataBlocked -> {
                varInt(STREAM_DATA_BLOCKED)
                varInt(frame.streamId)
                varInt(frame.maximumStreamData)
            }
            is QuicFrame.StreamsBlocked -> {
                varInt(if (frame.bidirectional) STREAMS_BLOCKED_BIDI else STREAMS_BLOCKED_UNI)
                varInt(frame.maximumStreams)
            }
            is QuicFrame.NewConnectionId -> {
                if (frame.retirePriorTo > frame.sequence) {
                    throw KQuicError.ProtocolViolation("retire_prior_to exceeds sequence")
                }
                if (frame.connectionId.size !in 1..20) {
                    throw KQuicError.ProtocolViolation("NEW_CONNECTION_ID has invalid ID length")
                }
                if (frame.statelessResetToken.size != 16) {
                    throw KQuicError.ProtocolViolation(
                        "Stateless reset token must contain 16 bytes"
                    )
                }
                varInt(NEW_CONNECTION_ID)
                varInt(frame.sequence)
                varInt(frame.retirePriorTo)
                byte(frame.connectionId.size)
                bytes(frame.connectionId)
                bytes(frame.statelessResetToken)
            }
            is QuicFrame.RetireConnectionId -> {
                varInt(RETIRE_CONNECTION_ID)
                varInt(frame.sequence)
            }
            is QuicFrame.Datagram -> {
                varInt(DATAGRAM_WITH_LENGTH)
                varInt(frame.data.size.toULong())
                bytes(frame.data)
            }
            is QuicFrame.ConnectionClose -> {
                val reason = frame.reason.encodeToByteArray()
                varInt(CONNECTION_CLOSE_TRANSPORT)
                varInt(frame.errorCode)
                varInt(frame.frameType)
                varInt(reason.size.toULong())
                bytes(reason)
            }
            QuicFrame.HandshakeDone -> varInt(HANDSHAKE_DONE)
        }
    }.build()

    fun decodeAll(payload: ByteArray): List<QuicFrame> {
        val reader = ByteReader(payload)
        val frames = mutableListOf<QuicFrame>()
        while (reader.remaining > 0) {
            when (val type = reader.varInt()) {
                PADDING -> Unit
                PING -> frames += QuicFrame.Ping
                ACK -> frames += decodeAck(reader)
                RESET_STREAM -> frames += QuicFrame.ResetStream(
                    streamId = reader.varInt(),
                    applicationErrorCode = reader.varInt(),
                    finalSize = reader.varInt()
                )
                STOP_SENDING -> frames += QuicFrame.StopSending(
                    streamId = reader.varInt(),
                    applicationErrorCode = reader.varInt()
                )
                CRYPTO -> frames += QuicFrame.Crypto(
                    offset = reader.varInt(),
                    data = reader.readBytes(reader.varInt().toLength())
                )
                MAX_DATA -> frames += QuicFrame.MaxData(reader.varInt())
                MAX_STREAM_DATA -> frames += QuicFrame.MaxStreamData(
                    streamId = reader.varInt(),
                    maximumStreamData = reader.varInt()
                )
                MAX_STREAMS_BIDI, MAX_STREAMS_UNI -> frames += QuicFrame.MaxStreams(
                    bidirectional = type == MAX_STREAMS_BIDI,
                    maximumStreams = reader.varInt().checkedStreamCount()
                )
                DATA_BLOCKED -> frames += QuicFrame.DataBlocked(reader.varInt())
                STREAM_DATA_BLOCKED -> frames += QuicFrame.StreamDataBlocked(
                    streamId = reader.varInt(),
                    maximumStreamData = reader.varInt()
                )
                STREAMS_BLOCKED_BIDI, STREAMS_BLOCKED_UNI -> frames += QuicFrame.StreamsBlocked(
                    bidirectional = type == STREAMS_BLOCKED_BIDI,
                    maximumStreams = reader.varInt().checkedStreamCount()
                )
                NEW_CONNECTION_ID -> {
                    val sequence = reader.varInt()
                    val retirePriorTo = reader.varInt()
                    if (retirePriorTo > sequence) {
                        throw KQuicError.ProtocolViolation("retire_prior_to exceeds sequence")
                    }
                    val connectionIdLength = reader.byte()
                    if (connectionIdLength !in 1..20) {
                        throw KQuicError.ProtocolViolation(
                            "NEW_CONNECTION_ID has invalid ID length"
                        )
                    }
                    frames += QuicFrame.NewConnectionId(
                        sequence = sequence,
                        retirePriorTo = retirePriorTo,
                        connectionId = reader.readBytes(connectionIdLength),
                        statelessResetToken = reader.readBytes(16)
                    )
                }
                RETIRE_CONNECTION_ID ->
                    frames += QuicFrame.RetireConnectionId(reader.varInt())
                DATAGRAM_WITHOUT_LENGTH -> frames += QuicFrame.Datagram(
                    reader.readBytes(reader.remaining)
                )
                DATAGRAM_WITH_LENGTH -> frames += QuicFrame.Datagram(
                    reader.readBytes(reader.varInt().toLength())
                )
                CONNECTION_CLOSE_TRANSPORT -> {
                    val errorCode = reader.varInt()
                    val frameType = reader.varInt()
                    val reason = reader.readBytes(reader.varInt().toLength()).decodeToString()
                    frames += QuicFrame.ConnectionClose(errorCode, frameType, reason)
                }
                HANDSHAKE_DONE -> frames += QuicFrame.HandshakeDone
                else -> {
                    if (type in STREAM_BASE..(STREAM_BASE + 0x07uL)) {
                        val offsetPresent = (type and 0x04uL) != 0uL
                        val lengthPresent = (type and 0x02uL) != 0uL
                        if (!lengthPresent) throw KQuicError.ProtocolViolation("STREAM without length is not accepted")
                        val streamId = reader.varInt()
                        val offset = if (offsetPresent) reader.varInt() else 0uL
                        val data = reader.readBytes(reader.varInt().toLength())
                        frames += QuicFrame.Stream(streamId, offset, (type and 0x01uL) != 0uL, data)
                    } else {
                        throw KQuicError.ProtocolViolation("Unsupported QUIC frame type $type")
                    }
                }
            }
        }
        return frames
    }

    private fun ByteWriter.encodeAck(frame: QuicFrame.Ack) {
        require(frame.ranges.isNotEmpty())
        require(frame.ranges.zipWithNext().all { (newer, older) -> newer.start > older.end + 1uL })
        val first = frame.ranges.first()
        varInt(ACK)
        varInt(first.end)
        varInt(frame.ackDelay)
        varInt((frame.ranges.size - 1).toULong())
        varInt(first.end - first.start)
        var previous = first
        for (range in frame.ranges.drop(1)) {
            varInt(previous.start - range.end - 2uL)
            varInt(range.end - range.start)
            previous = range
        }
    }

    private fun decodeAck(reader: ByteReader): QuicFrame.Ack {
        val largest = reader.varInt()
        val delay = reader.varInt()
        val count = reader.varInt().toLength()
        val firstLength = reader.varInt()
        if (firstLength > largest) throw KQuicError.ProtocolViolation("Invalid first ACK range")
        val ranges = mutableListOf(QuicAckRange(largest - firstLength, largest))
        var previous = ranges.first()
        repeat(count) {
            val gap = reader.varInt()
            val length = reader.varInt()
            if (previous.start < gap + 2uL) throw KQuicError.ProtocolViolation("Invalid ACK gap")
            val end = previous.start - gap - 2uL
            if (length > end) throw KQuicError.ProtocolViolation("Invalid ACK range")
            val range = QuicAckRange(end - length, end)
            ranges += range
            previous = range
        }
        return QuicFrame.Ack(ranges, delay)
    }

    private fun ULong.toLength(): Int {
        if (this > Int.MAX_VALUE.toULong()) throw KQuicError.ProtocolViolation("QUIC length exceeds Int range")
        return toInt()
    }

    private fun ULong.checkedStreamCount(): ULong {
        if (this > (1uL shl 60)) {
            throw KQuicError.ProtocolViolation("QUIC stream count exceeds 2^60")
        }
        return this
    }
}
