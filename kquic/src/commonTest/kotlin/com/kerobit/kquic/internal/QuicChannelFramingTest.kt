package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicDelivery
import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicLimits
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class QuicChannelFramingTest {
    private val limits = KQuicLimits(maxMessageSize = 16, maxChannelLabelBytes = 8)

    @Test
    fun decoder_accepts_every_possible_fragment_boundary() {
        val messages = listOf(
            QuicChannelMessage.Open(4uL, "game", KQuicDelivery.RELIABLE_ORDERED),
            QuicChannelMessage.Text(4uL, "hello"),
            QuicChannelMessage.Bytes(4uL, byteArrayOf(1, 2, 3)),
            QuicChannelMessage.Close(4uL)
        )
        val encoded = messages
            .map { QuicChannelFrames.encode(it, limits) }
            .reduce(ByteArray::plus)

        for (split in 0..encoded.size) {
            val decoder = QuicChannelFrameDecoder(limits)
            val decoded = decoder.feed(encoded.copyOfRange(0, split)) +
                decoder.feed(encoded.copyOfRange(split, encoded.size))

            assertEquals(4, decoded.size)
            assertEquals(messages[0], decoded[0])
            assertEquals(messages[1], decoded[1])
            assertContentEquals(
                (messages[2] as QuicChannelMessage.Bytes).payload,
                (decoded[2] as QuicChannelMessage.Bytes).payload
            )
            assertEquals(messages[3], decoded[3])
        }
    }

    @Test
    fun incomplete_frame_is_buffered_until_the_payload_arrives() {
        val frame = QuicChannelFrames.encode(QuicChannelMessage.Text(8uL, "hello"), limits)
        val decoder = QuicChannelFrameDecoder(limits)

        assertTrue(decoder.feed(frame.copyOfRange(0, frame.lastIndex)).isEmpty())
        assertEquals(
            QuicChannelMessage.Text(8uL, "hello"),
            decoder.feed(frame.copyOfRange(frame.lastIndex, frame.size)).single()
        )
    }

    @Test
    fun declared_payload_over_the_limit_is_rejected_before_it_is_buffered() {
        val oversizedHeader = ByteWriter().apply {
            byte(1)
            varInt(4uL)
            varInt(17uL)
        }.build()

        assertFailsWith<KQuicError.ProtocolViolation> {
            QuicChannelFrameDecoder(limits).feed(oversizedHeader)
        }
    }
}
