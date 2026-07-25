package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuicPacketTest {
    @Test
    fun initial_long_header_roundTrips_connection_ids_token_and_length() {
        val destinationId = byteArrayOf(0x83.toByte(), 0x94.toByte(), 0xc8.toByte(), 0xf0.toByte())
        val sourceId = byteArrayOf(1, 2, 3)
        val token = byteArrayOf(9, 8)
        val encoded = QuicLongHeaders.encode(
            type = QuicLongPacketType.INITIAL,
            destinationConnectionId = destinationId,
            sourceConnectionId = sourceId,
            token = token,
            payloadLength = 1_200uL,
            packetNumberLength = 2
        )
        val packet = encoded + ByteArray(1_200)

        val decoded = QuicLongHeaders.decode(packet)

        assertEquals(QuicLongPacketType.INITIAL, decoded.type)
        assertEquals(QUIC_VERSION_1, decoded.version)
        assertContentEquals(destinationId, decoded.destinationConnectionId)
        assertContentEquals(sourceId, decoded.sourceConnectionId)
        assertContentEquals(token, decoded.token)
        assertEquals(1_200uL, decoded.payloadLength)
        assertEquals(2, decoded.packetNumberLength)
        assertEquals(encoded.size, decoded.packetNumberOffset)
    }

    @Test
    fun initial_header_uses_the_quic_v1_wire_layout() {
        val encoded = QuicLongHeaders.encode(
            type = QuicLongPacketType.INITIAL,
            destinationConnectionId = byteArrayOf(
                0x83.toByte(),
                0x94.toByte(),
                0xc8.toByte(),
                0xf0.toByte()
            ),
            sourceConnectionId = ByteArray(0),
            payloadLength = 1_200uL,
            packetNumberLength = 2
        )

        assertContentEquals(
            byteArrayOf(
                0xc1.toByte(),
                0x00, 0x00, 0x00, 0x01,
                0x04,
                0x83.toByte(), 0x94.toByte(), 0xc8.toByte(), 0xf0.toByte(),
                0x00,
                0x00,
                0x44, 0xb0.toByte()
            ),
            encoded
        )
    }

    @Test
    fun packet_number_reconstruction_matches_rfc_9000_appendix_a3() {
        assertEquals(
            0xa82f9b32uL,
            QuicPacketNumbers.decode(
                largestReceived = 0xa82f30eauL,
                truncated = 0x9b32uL,
                encodedLength = 2
            )
        )
        assertEquals(0x9b32uL, QuicPacketNumbers.truncate(0xa82f9b32uL, 2))
    }

    @Test
    fun decoder_rejects_truncated_payload_and_oversized_connection_ids() {
        val header = QuicLongHeaders.encode(
            type = QuicLongPacketType.HANDSHAKE,
            destinationConnectionId = ByteArray(0),
            sourceConnectionId = ByteArray(0),
            payloadLength = 32uL,
            packetNumberLength = 1
        )
        assertFailsWith<KQuicError.ProtocolViolation> {
            QuicLongHeaders.decode(header)
        }
        assertFailsWith<KQuicError.ProtocolViolation> {
            QuicLongHeaders.encode(
                type = QuicLongPacketType.INITIAL,
                destinationConnectionId = ByteArray(21),
                sourceConnectionId = ByteArray(0),
                payloadLength = 1uL,
                packetNumberLength = 1
            )
        }
    }
}
