package com.kerobit.kquic.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class QuicCodecTest {
    @Test
    fun varints_roundTrip_at_every_wire_width() {
        val values = listOf(0uL, 63uL, 64uL, 16_383uL, 16_384uL, 1_073_741_823uL, 1_073_741_824uL, (1uL shl 62) - 1uL)
        values.forEach { value ->
            val encoded = ByteWriter().apply { varInt(value) }.build()
            assertEquals(value, ByteReader(encoded).varInt())
        }
    }

    @Test
    fun frames_roundTrip_ack_stream_and_datagram() {
        val ack = QuicFrame.Ack(
            ranges = listOf(QuicAckRange(90uL, 100uL), QuicAckRange(70uL, 80uL)),
            ackDelay = 4uL
        )
        assertEquals(ack, QuicFrames.decodeAll(QuicFrames.encode(ack)).single())

        val stream = QuicFrame.Stream(4uL, 8uL, true, byteArrayOf(1, 2, 3))
        val parsedStream = QuicFrames.decodeAll(QuicFrames.encode(stream)).single() as QuicFrame.Stream
        assertEquals(stream.streamId, parsedStream.streamId)
        assertEquals(stream.offset, parsedStream.offset)
        assertEquals(stream.fin, parsedStream.fin)
        assertContentEquals(stream.data, parsedStream.data)

        val datagram = QuicFrame.Datagram(byteArrayOf(9, 8, 7))
        val parsedDatagram = QuicFrames.decodeAll(QuicFrames.encode(datagram)).single() as QuicFrame.Datagram
        assertContentEquals(datagram.data, parsedDatagram.data)
    }

    @Test
    fun transport_parameters_reject_duplicates() {
        val bytes = ByteWriter().apply {
            varInt(1uL); varInt(1uL); byte(1)
            varInt(1uL); varInt(1uL); byte(2)
        }.build()
        assertFailsWith<IllegalStateException> { QuicTransportParameters.decode(bytes) }
    }
}
