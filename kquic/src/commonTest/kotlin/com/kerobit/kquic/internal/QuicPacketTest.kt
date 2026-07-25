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

    @Test
    fun invariant_parser_handles_long_and_short_headers_before_unprotection() {
        val longPacket = QuicLongHeaders.encode(
            type = QuicLongPacketType.HANDSHAKE,
            destinationConnectionId = byteArrayOf(1, 2),
            sourceConnectionId = byteArrayOf(3),
            payloadLength = 5uL,
            packetNumberLength = 1
        ) + ByteArray(5)
        val long = QuicInvariantHeaders.decode(longPacket) as QuicInvariantHeader.Long
        assertEquals(QUIC_VERSION_1, long.version)
        assertContentEquals(byteArrayOf(1, 2), long.destinationConnectionId)

        val shortPacket = byteArrayOf(0x40, 9, 8, 0, 1, 2)
        val short = QuicInvariantHeaders.decode(
            shortPacket,
            shortHeaderDestinationIdLength = 2
        ) as QuicInvariantHeader.Short
        assertContentEquals(byteArrayOf(9, 8), short.destinationConnectionId)
        assertEquals(3, short.packetNumberOffset)
    }

    @Test
    fun coalesced_long_header_packets_are_split_using_the_length_field() {
        fun packet(id: Byte, payloadSize: Int): ByteArray =
            QuicLongHeaders.encode(
                type = QuicLongPacketType.HANDSHAKE,
                destinationConnectionId = byteArrayOf(id),
                sourceConnectionId = byteArrayOf(),
                payloadLength = payloadSize.toULong(),
                packetNumberLength = 1
            ) + ByteArray(payloadSize)

        val first = packet(1, 18)
        val second = packet(2, 21)
        val split = QuicCoalescedPackets.split(first + second)
        assertEquals(2, split.size)
        assertContentEquals(first, split[0])
        assertContentEquals(second, split[1])
    }

    @Test
    fun header_protection_unmasks_first_byte_and_packet_number() {
        val packet = ByteArray(24)
        packet[0] = 0xc1.toByte()
        packet[5] = (0x12 xor 0x44).toByte()
        packet[6] = (0x34 xor 0x55).toByte()
        val unprotected = QuicHeaderProtection.remove(
            protectedPacket = packet,
            packetNumberOffset = 5,
            longHeader = true,
            mask = byteArrayOf(0, 0x44, 0x55, 0, 0)
        )
        assertEquals(0xc1, unprotected[0].toInt() and 0xff)
        assertEquals(0x12, unprotected[5].toInt() and 0xff)
        assertEquals(0x34, unprotected[6].toInt() and 0xff)
        assertEquals(16, QuicHeaderProtection.sample(packet, 4).size)
    }
}
