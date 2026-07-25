package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError

internal class ByteWriter {
    private val bytes = ArrayList<Byte>()

    fun byte(value: Int) {
        require(value in 0..255)
        bytes += value.toByte()
    }

    fun bytes(value: ByteArray) {
        value.forEach { bytes += it }
    }

    fun varInt(value: ULong) {
        when {
            value < (1uL shl 6) -> byte(value.toInt())
            value < (1uL shl 14) -> {
                byte(0x40 or ((value shr 8).toInt() and 0x3f))
                byte(value.toInt() and 0xff)
            }
            value < (1uL shl 30) -> {
                byte(0x80 or ((value shr 24).toInt() and 0x3f))
                byte((value shr 16).toInt() and 0xff)
                byte((value shr 8).toInt() and 0xff)
                byte(value.toInt() and 0xff)
            }
            value < (1uL shl 62) -> {
                byte(0xc0 or ((value shr 56).toInt() and 0x3f))
                for (shift in 48 downTo 0 step 8) byte((value shr shift).toInt() and 0xff)
            }
            else -> throw KQuicError.ProtocolViolation("QUIC varint exceeds 62 bits")
        }
    }

    fun build(): ByteArray = bytes.toByteArray()
}

internal class ByteReader(private val bytes: ByteArray) {
    private var position = 0
    val remaining: Int get() = bytes.size - position

    fun byte(): Int {
        if (position >= bytes.size) throw KQuicError.ProtocolViolation("Unexpected end of QUIC payload")
        return bytes[position++].toInt() and 0xff
    }

    fun readBytes(length: Int): ByteArray {
        if (length < 0 || length > remaining) throw KQuicError.ProtocolViolation("Invalid QUIC payload length")
        val start = position
        position += length
        return bytes.copyOfRange(start, position)
    }

    fun varInt(): ULong {
        val first = byte()
        val length = 1 shl (first ushr 6)
        var result = (first and 0x3f).toULong()
        repeat(length - 1) { result = (result shl 8) or byte().toULong() }
        return result
    }
}
