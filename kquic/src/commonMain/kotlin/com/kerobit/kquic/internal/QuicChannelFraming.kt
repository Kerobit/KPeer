package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicDelivery
import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicLimits

/**
 * Application framing carried by KQuic's authenticated control and data streams.
 *
 * QUIC stream reads do not preserve write boundaries, so [QuicChannelFrameDecoder] accepts
 * arbitrary fragments and only emits complete messages.
 */
internal sealed interface QuicChannelMessage {
    val channelId: ULong

    data class Open(
        override val channelId: ULong,
        val label: String,
        val delivery: KQuicDelivery
    ) : QuicChannelMessage

    data class Bytes(
        override val channelId: ULong,
        val payload: ByteArray
    ) : QuicChannelMessage

    data class Text(
        override val channelId: ULong,
        val text: String
    ) : QuicChannelMessage

    data class Close(
        override val channelId: ULong
    ) : QuicChannelMessage
}

internal object QuicChannelFrames {
    private const val OPEN = 0
    private const val BYTES = 1
    private const val TEXT = 2
    private const val CLOSE = 3

    fun encode(message: QuicChannelMessage, limits: KQuicLimits): ByteArray = ByteWriter().apply {
        when (message) {
            is QuicChannelMessage.Open -> {
                val label = message.label.encodeToByteArray()
                if (label.isEmpty() || label.size > limits.maxChannelLabelBytes) {
                    throw KQuicError.ProtocolViolation(
                        "Channel label must contain 1..${limits.maxChannelLabelBytes} UTF-8 bytes"
                    )
                }
                byte(OPEN)
                varInt(message.channelId)
                byte(message.delivery.wireValue)
                varInt(label.size.toULong())
                bytes(label)
            }
            is QuicChannelMessage.Bytes -> {
                checkPayloadSize(message.payload.size, limits)
                byte(BYTES)
                varInt(message.channelId)
                varInt(message.payload.size.toULong())
                bytes(message.payload)
            }
            is QuicChannelMessage.Text -> {
                val payload = message.text.encodeToByteArray()
                checkPayloadSize(payload.size, limits)
                byte(TEXT)
                varInt(message.channelId)
                varInt(payload.size.toULong())
                bytes(payload)
            }
            is QuicChannelMessage.Close -> {
                byte(CLOSE)
                varInt(message.channelId)
            }
        }
    }.build()

    internal fun typeOf(value: Int): Int = when (value) {
        OPEN, BYTES, TEXT, CLOSE -> value
        else -> throw KQuicError.ProtocolViolation("Unknown KQuic channel frame type $value")
    }

    internal fun deliveryOf(value: Int): KQuicDelivery = when (value) {
        0 -> KQuicDelivery.RELIABLE_ORDERED
        1 -> KQuicDelivery.UNRELIABLE
        else -> throw KQuicError.ProtocolViolation("Unknown KQuic delivery mode $value")
    }

    private val KQuicDelivery.wireValue: Int
        get() = when (this) {
            KQuicDelivery.RELIABLE_ORDERED -> 0
            KQuicDelivery.UNRELIABLE -> 1
        }

    private fun checkPayloadSize(size: Int, limits: KQuicLimits) {
        if (size > limits.maxMessageSize) {
            throw KQuicError.MessageTooLarge(size, limits.maxMessageSize)
        }
    }
}

internal class QuicChannelFrameDecoder(
    private val limits: KQuicLimits
) {
    private var buffered = ByteArray(0)

    fun feed(fragment: ByteArray): List<QuicChannelMessage> {
        if (fragment.isNotEmpty()) buffered += fragment
        val decoded = mutableListOf<QuicChannelMessage>()
        var consumed = 0

        while (consumed < buffered.size) {
            val cursor = FrameCursor(buffered, consumed)
            val message = try {
                decodeOne(cursor)
            } catch (_: IncompleteFrame) {
                break
            }
            consumed = cursor.position
            decoded += message
        }

        if (consumed > 0) buffered = buffered.copyOfRange(consumed, buffered.size)
        val maximumBufferedFrame = maxOf(limits.maxMessageSize, limits.maxChannelLabelBytes) + 32
        if (buffered.size > maximumBufferedFrame) {
            throw KQuicError.ProtocolViolation("KQuic channel frame exceeds configured limits")
        }
        return decoded
    }

    private fun decodeOne(cursor: FrameCursor): QuicChannelMessage {
        return when (QuicChannelFrames.typeOf(cursor.byte())) {
            0 -> {
                val channelId = cursor.varInt()
                val delivery = QuicChannelFrames.deliveryOf(cursor.byte())
                val labelLength = cursor.varInt().checkedLength(
                    maximum = limits.maxChannelLabelBytes,
                    field = "channel label"
                )
                if (labelLength == 0) {
                    throw KQuicError.ProtocolViolation("Channel label cannot be empty")
                }
                val label = cursor.bytes(labelLength).decodeUtf8("channel label")
                QuicChannelMessage.Open(channelId, label, delivery)
            }
            1 -> QuicChannelMessage.Bytes(
                channelId = cursor.varInt(),
                payload = cursor.bytes(cursor.payloadLength())
            )
            2 -> QuicChannelMessage.Text(
                channelId = cursor.varInt(),
                text = cursor.bytes(cursor.payloadLength()).decodeUtf8("text message")
            )
            3 -> QuicChannelMessage.Close(cursor.varInt())
            else -> error("Channel frame type was validated")
        }
    }

    private fun FrameCursor.payloadLength(): Int =
        varInt().checkedLength(limits.maxMessageSize, "message")

    private fun ULong.checkedLength(maximum: Int, field: String): Int {
        if (this > maximum.toULong()) {
            throw KQuicError.ProtocolViolation("$field length exceeds configured maximum $maximum")
        }
        return toInt()
    }

    private fun ByteArray.decodeUtf8(field: String): String = try {
        decodeToString(throwOnInvalidSequence = true)
    } catch (_: Exception) {
        throw KQuicError.ProtocolViolation("Invalid UTF-8 in KQuic $field")
    }

    private class FrameCursor(
        private val source: ByteArray,
        var position: Int
    ) {
        fun byte(): Int {
            if (position >= source.size) throw IncompleteFrame
            return source[position++].toInt() and 0xff
        }

        fun bytes(length: Int): ByteArray {
            if (source.size - position < length) throw IncompleteFrame
            val result = source.copyOfRange(position, position + length)
            position += length
            return result
        }

        fun varInt(): ULong {
            val first = byte()
            val length = 1 shl (first ushr 6)
            var result = (first and 0x3f).toULong()
            repeat(length - 1) { result = (result shl 8) or byte().toULong() }
            return result
        }
    }

    private data object IncompleteFrame : Throwable()
}
