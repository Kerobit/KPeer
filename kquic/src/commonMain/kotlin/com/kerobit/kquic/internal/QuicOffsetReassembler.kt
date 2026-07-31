package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError

/**
 * Bounded offset reassembly shared by CRYPTO and STREAM receive paths.
 *
 * Duplicate bytes are accepted only when their contents agree. This matters for CRYPTO because
 * feeding conflicting retransmissions into TLS would make authentication depend on packet order.
 */
internal class QuicOffsetReassembler(
    private val maxBufferedBytes: Int,
    maxOffset: ULong
) {
    private data class Fragment(val offset: ULong, val bytes: ByteArray) {
        val end: ULong get() = offset + bytes.size.toULong()
    }

    private val fragments = mutableListOf<Fragment>()
    private var maxOffset = maxOffset
    private var bufferedBytes = 0
    var consumedOffset: ULong = 0uL
        private set
    var finalOffset: ULong? = null
        private set

    init {
        require(maxBufferedBytes > 0)
        require(maxOffset > 0uL)
    }

    /**
     * Adds one fragment and returns newly contiguous data. An empty result means a gap remains.
     */
    fun add(offset: ULong, data: ByteArray, fin: Boolean = false): ByteArray {
        val end = checkedEnd(offset, data.size)
        if (end > maxOffset) {
            throw KQuicError.ProtocolViolation("QUIC offset $end exceeds receive limit $maxOffset")
        }
        if (fin) {
            val existing = finalOffset
            if (existing != null && existing != end) {
                throw KQuicError.ProtocolViolation("Conflicting final size")
            }
            if (end < consumedOffset || fragments.any { it.end > end }) {
                throw KQuicError.ProtocolViolation("Final size is smaller than received data")
            }
            finalOffset = end
        } else if (finalOffset?.let { end > it } == true) {
            throw KQuicError.ProtocolViolation("Data exceeds the final size")
        }

        if (data.isNotEmpty() && end > consumedOffset) {
            val trim = if (offset < consumedOffset) (consumedOffset - offset).toInt() else 0
            fragments += Fragment(offset + trim.toULong(), data.copyOfRange(trim, data.size))
            normalize()
        }
        return consumeContiguous()
    }

    val complete: Boolean
        get() = finalOffset == consumedOffset

    fun updateMaximumOffset(value: ULong) {
        if (value > maxOffset) maxOffset = value
    }

    private fun normalize() {
        fragments.sortBy(Fragment::offset)
        val merged = mutableListOf<Fragment>()
        fragments.forEach { incoming ->
            val previous = merged.lastOrNull()
            if (previous == null || incoming.offset > previous.end) {
                merged += incoming
            } else {
                val overlap = (previous.end - incoming.offset).toInt().coerceAtMost(incoming.bytes.size)
                repeat(overlap) { index ->
                    val previousIndex = (incoming.offset - previous.offset).toInt() + index
                    if (previous.bytes[previousIndex] != incoming.bytes[index]) {
                        throw KQuicError.ProtocolViolation("Conflicting bytes at the same QUIC offset")
                    }
                }
                if (incoming.end > previous.end) {
                    merged[merged.lastIndex] = Fragment(
                        previous.offset,
                        previous.bytes + incoming.bytes.copyOfRange(overlap, incoming.bytes.size)
                    )
                }
            }
        }
        fragments.clear()
        fragments += merged
        bufferedBytes = fragments.sumOf { it.bytes.size }
        if (bufferedBytes > maxBufferedBytes) {
            throw KQuicError.ProtocolViolation("QUIC reassembly buffer exceeds configured limit")
        }
    }

    private fun consumeContiguous(): ByteArray {
        if (fragments.firstOrNull()?.offset != consumedOffset) return ByteArray(0)
        val output = mutableListOf<ByteArray>()
        while (fragments.firstOrNull()?.offset == consumedOffset) {
            val fragment = fragments.removeAt(0)
            output += fragment.bytes
            bufferedBytes -= fragment.bytes.size
            consumedOffset += fragment.bytes.size.toULong()
        }
        val result = ByteArray(output.sumOf(ByteArray::size))
        var position = 0
        output.forEach {
            it.copyInto(result, position)
            position += it.size
        }
        return result
    }

    private fun checkedEnd(offset: ULong, size: Int): ULong {
        val length = size.toULong()
        if (offset > ULong.MAX_VALUE - length) {
            throw KQuicError.ProtocolViolation("QUIC offset overflow")
        }
        return offset + length
    }
}
