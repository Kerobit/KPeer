package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError

internal const val QUIC_VERSION_1: UInt = 0x00000001u
private const val MAX_CONNECTION_ID_LENGTH = 20
private val MAX_PACKET_NUMBER = (1uL shl 62) - 1uL

internal enum class QuicLongPacketType {
    INITIAL,
    ZERO_RTT,
    HANDSHAKE
}

/**
 * The fields of a QUIC v1 long header that are available after header protection is removed.
 *
 * [payloadLength] is the QUIC Length field, so it includes the packet number and protected
 * payload. [packetNumberOffset] points at the first packet-number byte in the full datagram.
 */
internal data class QuicLongHeader(
    val type: QuicLongPacketType,
    val version: UInt,
    val destinationConnectionId: ByteArray,
    val sourceConnectionId: ByteArray,
    val token: ByteArray,
    val payloadLength: ULong,
    val packetNumberLength: Int,
    val packetNumberOffset: Int
)

/**
 * Encodes and parses QUIC v1 long headers. Payload encryption and header protection are separate
 * stages because the header-protection sample comes from the encrypted packet.
 */
internal object QuicLongHeaders {
    fun encode(
        type: QuicLongPacketType,
        destinationConnectionId: ByteArray,
        sourceConnectionId: ByteArray,
        token: ByteArray = ByteArray(0),
        payloadLength: ULong,
        packetNumberLength: Int
    ): ByteArray {
        validateConnectionId(destinationConnectionId)
        validateConnectionId(sourceConnectionId)
        require(packetNumberLength in 1..4)
        require(payloadLength >= packetNumberLength.toULong())
        if (type != QuicLongPacketType.INITIAL && token.isNotEmpty()) {
            throw KQuicError.ProtocolViolation("Only Initial packets carry a token")
        }

        return ByteWriter().apply {
            byte(0xc0 or (type.wireValue shl 4) or (packetNumberLength - 1))
            uint32(QUIC_VERSION_1)
            byte(destinationConnectionId.size)
            bytes(destinationConnectionId)
            byte(sourceConnectionId.size)
            bytes(sourceConnectionId)
            if (type == QuicLongPacketType.INITIAL) {
                varInt(token.size.toULong())
                bytes(token)
            }
            varInt(payloadLength)
        }.build()
    }

    fun decode(packet: ByteArray): QuicLongHeader {
        val reader = PacketCursor(packet)
        val first = reader.byte()
        if ((first and 0x80) == 0) {
            throw KQuicError.ProtocolViolation("Expected a QUIC long header")
        }
        if ((first and 0x40) == 0) {
            throw KQuicError.ProtocolViolation("QUIC fixed bit is not set")
        }
        val version = reader.uint32()
        if (version != QUIC_VERSION_1) {
            throw KQuicError.ProtocolViolation("Unsupported QUIC version $version")
        }
        val type = packetTypeFromWire((first ushr 4) and 0x03)
        val destinationConnectionId = reader.connectionId()
        val sourceConnectionId = reader.connectionId()
        val token = if (type == QuicLongPacketType.INITIAL) {
            reader.bytes(reader.varInt().checkedPacketLength("Initial token"))
        } else {
            ByteArray(0)
        }
        val payloadLength = reader.varInt()
        val packetNumberLength = (first and 0x03) + 1
        if (payloadLength < packetNumberLength.toULong()) {
            throw KQuicError.ProtocolViolation("QUIC payload is shorter than its packet number")
        }
        if (payloadLength > reader.remaining.toULong()) {
            throw KQuicError.ProtocolViolation("Truncated QUIC long-header packet")
        }
        return QuicLongHeader(
            type = type,
            version = version,
            destinationConnectionId = destinationConnectionId,
            sourceConnectionId = sourceConnectionId,
            token = token,
            payloadLength = payloadLength,
            packetNumberLength = packetNumberLength,
            packetNumberOffset = reader.position
        )
    }

    private val QuicLongPacketType.wireValue: Int
        get() = when (this) {
            QuicLongPacketType.INITIAL -> 0
            QuicLongPacketType.ZERO_RTT -> 1
            QuicLongPacketType.HANDSHAKE -> 2
        }

    private fun packetTypeFromWire(value: Int): QuicLongPacketType = when (value) {
        0 -> QuicLongPacketType.INITIAL
        1 -> QuicLongPacketType.ZERO_RTT
        2 -> QuicLongPacketType.HANDSHAKE
        3 -> throw KQuicError.ProtocolViolation("Retry packets require integrity-tag parsing")
        else -> error("Long packet type is two bits")
    }

    private fun validateConnectionId(value: ByteArray) {
        if (value.size > MAX_CONNECTION_ID_LENGTH) {
            throw KQuicError.ProtocolViolation(
                "QUIC connection ID exceeds $MAX_CONNECTION_ID_LENGTH bytes"
            )
        }
    }

    private fun ByteWriter.uint32(value: UInt) {
        for (shift in 24 downTo 0 step 8) {
            byte(((value shr shift) and 0xffu).toInt())
        }
    }

    private class PacketCursor(private val source: ByteArray) {
        var position: Int = 0
            private set
        val remaining: Int get() = source.size - position

        fun byte(): Int {
            if (position >= source.size) {
                throw KQuicError.ProtocolViolation("Unexpected end of QUIC packet")
            }
            return source[position++].toInt() and 0xff
        }

        fun bytes(length: Int): ByteArray {
            if (length < 0 || length > remaining) {
                throw KQuicError.ProtocolViolation("Invalid QUIC packet field length")
            }
            val result = source.copyOfRange(position, position + length)
            position += length
            return result
        }

        fun uint32(): UInt {
            var result = 0u
            repeat(4) { result = (result shl 8) or byte().toUInt() }
            return result
        }

        fun connectionId(): ByteArray {
            val length = byte()
            if (length > MAX_CONNECTION_ID_LENGTH) {
                throw KQuicError.ProtocolViolation(
                    "QUIC connection ID exceeds $MAX_CONNECTION_ID_LENGTH bytes"
                )
            }
            return bytes(length)
        }

        fun varInt(): ULong {
            val first = byte()
            val length = 1 shl (first ushr 6)
            var result = (first and 0x3f).toULong()
            repeat(length - 1) { result = (result shl 8) or byte().toULong() }
            return result
        }

    }
}

private fun ULong.checkedPacketLength(field: String): Int {
    if (this > Int.MAX_VALUE.toULong()) {
        throw KQuicError.ProtocolViolation("$field is too large")
    }
    return toInt()
}

internal object QuicPacketNumbers {
    fun truncate(packetNumber: ULong, encodedLength: Int): ULong {
        require(packetNumber <= MAX_PACKET_NUMBER)
        require(encodedLength in 1..4)
        val mask = (1uL shl (encodedLength * 8)) - 1uL
        return packetNumber and mask
    }

    /** Reconstructs a full packet number using RFC 9000 Appendix A.3. */
    fun decode(largestReceived: ULong?, truncated: ULong, encodedLength: Int): ULong {
        require(encodedLength in 1..4)
        val expected = largestReceived?.let {
            require(it < MAX_PACKET_NUMBER)
            it + 1uL
        } ?: 0uL
        val window = 1uL shl (encodedLength * 8)
        val halfWindow = window / 2uL
        val mask = window - 1uL
        require(truncated <= mask)
        var candidate = (expected and mask.inv()) or truncated

        if (expected >= halfWindow &&
            candidate <= expected - halfWindow &&
            candidate <= MAX_PACKET_NUMBER - window
        ) {
            candidate += window
        } else if (candidate > expected + halfWindow && candidate >= window) {
            candidate -= window
        }
        if (candidate > MAX_PACKET_NUMBER) {
            throw KQuicError.ProtocolViolation("Decoded QUIC packet number exceeds 62 bits")
        }
        return candidate
    }
}
