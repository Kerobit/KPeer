package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError

internal sealed interface QuicFrame {
    data object Ping : QuicFrame
    data class Ack(val ranges: List<QuicAckRange>, val ackDelay: ULong) : QuicFrame
    data class Crypto(val offset: ULong, val data: ByteArray) : QuicFrame
    data class Stream(val streamId: ULong, val offset: ULong, val fin: Boolean, val data: ByteArray) : QuicFrame
    data class Datagram(val data: ByteArray) : QuicFrame
    data class ConnectionClose(val errorCode: ULong, val frameType: ULong, val reason: String) : QuicFrame
}

/** Inclusive packet-number range. QUIC ACK ranges are ordered newest to oldest. */
internal data class QuicAckRange(val start: ULong, val end: ULong) {
    init { require(start <= end) }
}

internal object QuicFrames {
    private const val PADDING = 0x00uL
    private const val PING = 0x01uL
    private const val ACK = 0x02uL
    private const val CRYPTO = 0x06uL
    private const val STREAM_BASE = 0x08uL
    private const val CONNECTION_CLOSE_TRANSPORT = 0x1cuL
    private const val DATAGRAM_WITH_LENGTH = 0x31uL

    fun encode(frame: QuicFrame): ByteArray = ByteWriter().apply {
        when (frame) {
            QuicFrame.Ping -> varInt(PING)
            is QuicFrame.Ack -> encodeAck(frame)
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
                CRYPTO -> frames += QuicFrame.Crypto(
                    offset = reader.varInt(),
                    data = reader.readBytes(reader.varInt().toLength())
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
}
