package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError

/** Encodes the RFC 9000 transport-parameter vector while preserving unknown extensions. */
internal class QuicTransportParameters(private val values: Map<ULong, ByteArray>) {
    fun value(id: ULong): ByteArray? = values[id]?.copyOf()

    fun encode(): ByteArray = ByteWriter().apply {
        values.entries.sortedBy { it.key }.forEach { (id, value) ->
            varInt(id)
            varInt(value.size.toULong())
            bytes(value)
        }
    }.build()

    companion object {
        fun decode(payload: ByteArray): QuicTransportParameters {
            val reader = ByteReader(payload)
            val values = linkedMapOf<ULong, ByteArray>()
            while (reader.remaining > 0) {
                val id = reader.varInt()
                val length = reader.varInt()
                if (length > Int.MAX_VALUE.toULong()) throw KQuicError.ProtocolViolation("Transport parameter too large")
                if (values.put(id, reader.readBytes(length.toInt())) != null) {
                    throw KQuicError.ProtocolViolation("Duplicate transport parameter $id")
                }
            }
            return QuicTransportParameters(values)
        }
    }
}
