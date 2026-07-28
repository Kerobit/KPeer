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

    fun decoded(): QuicTransportParameterValues {
        val result = QuicTransportParameterValues(
            originalDestinationConnectionId = value(ORIGINAL_DESTINATION_CONNECTION_ID),
            maxIdleTimeoutMs = varIntValue(MAX_IDLE_TIMEOUT) ?: 0uL,
            maxUdpPayloadSize = varIntValue(MAX_UDP_PAYLOAD_SIZE) ?: 65_527uL,
            initialMaxData = varIntValue(INITIAL_MAX_DATA) ?: 0uL,
            initialMaxStreamDataBidiLocal = varIntValue(INITIAL_MAX_STREAM_DATA_BIDI_LOCAL) ?: 0uL,
            initialMaxStreamDataBidiRemote = varIntValue(INITIAL_MAX_STREAM_DATA_BIDI_REMOTE) ?: 0uL,
            initialMaxStreamDataUni = varIntValue(INITIAL_MAX_STREAM_DATA_UNI) ?: 0uL,
            initialMaxStreamsBidi = varIntValue(INITIAL_MAX_STREAMS_BIDI) ?: 0uL,
            initialMaxStreamsUni = varIntValue(INITIAL_MAX_STREAMS_UNI) ?: 0uL,
            ackDelayExponent = varIntValue(ACK_DELAY_EXPONENT) ?: 3uL,
            maxAckDelayMs = varIntValue(MAX_ACK_DELAY) ?: 25uL,
            activeConnectionIdLimit = varIntValue(ACTIVE_CONNECTION_ID_LIMIT) ?: 2uL,
            initialSourceConnectionId = value(INITIAL_SOURCE_CONNECTION_ID)
                ?: throw KQuicError.ProtocolViolation("Missing initial_source_connection_id transport parameter"),
            retrySourceConnectionId = value(RETRY_SOURCE_CONNECTION_ID),
            maxDatagramFrameSize = varIntValue(MAX_DATAGRAM_FRAME_SIZE)
        )
        result.validate()
        return result
    }

    private fun varIntValue(id: ULong): ULong? = value(id)?.let { encoded ->
        val reader = ByteReader(encoded)
        val value = reader.varInt()
        if (reader.remaining != 0) {
            throw KQuicError.ProtocolViolation("Transport parameter $id has trailing bytes")
        }
        value
    }

    companion object {
        fun encode(values: QuicTransportParameterValues): ByteArray {
            values.validate()
            val parameters = linkedMapOf<ULong, ByteArray>()
            values.originalDestinationConnectionId?.let {
                parameters[ORIGINAL_DESTINATION_CONNECTION_ID] = it.copyOf()
            }
            parameters[MAX_IDLE_TIMEOUT] = encodeVarInt(values.maxIdleTimeoutMs)
            parameters[MAX_UDP_PAYLOAD_SIZE] = encodeVarInt(values.maxUdpPayloadSize)
            parameters[INITIAL_MAX_DATA] = encodeVarInt(values.initialMaxData)
            parameters[INITIAL_MAX_STREAM_DATA_BIDI_LOCAL] =
                encodeVarInt(values.initialMaxStreamDataBidiLocal)
            parameters[INITIAL_MAX_STREAM_DATA_BIDI_REMOTE] =
                encodeVarInt(values.initialMaxStreamDataBidiRemote)
            parameters[INITIAL_MAX_STREAM_DATA_UNI] = encodeVarInt(values.initialMaxStreamDataUni)
            parameters[INITIAL_MAX_STREAMS_BIDI] = encodeVarInt(values.initialMaxStreamsBidi)
            parameters[INITIAL_MAX_STREAMS_UNI] = encodeVarInt(values.initialMaxStreamsUni)
            parameters[ACK_DELAY_EXPONENT] = encodeVarInt(values.ackDelayExponent)
            parameters[MAX_ACK_DELAY] = encodeVarInt(values.maxAckDelayMs)
            parameters[ACTIVE_CONNECTION_ID_LIMIT] =
                encodeVarInt(values.activeConnectionIdLimit)
            parameters[INITIAL_SOURCE_CONNECTION_ID] =
                values.initialSourceConnectionId.copyOf()
            values.retrySourceConnectionId?.let {
                parameters[RETRY_SOURCE_CONNECTION_ID] = it.copyOf()
            }
            values.maxDatagramFrameSize?.let {
                parameters[MAX_DATAGRAM_FRAME_SIZE] = encodeVarInt(it)
            }
            return QuicTransportParameters(parameters).encode()
        }

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

        private fun encodeVarInt(value: ULong): ByteArray =
            ByteWriter().apply { varInt(value) }.build()
    }
}

internal data class QuicTransportParameterValues(
    val originalDestinationConnectionId: ByteArray? = null,
    val maxIdleTimeoutMs: ULong = 30_000uL,
    val maxUdpPayloadSize: ULong = 1_200uL,
    val initialMaxData: ULong,
    val initialMaxStreamDataBidiLocal: ULong,
    val initialMaxStreamDataBidiRemote: ULong,
    val initialMaxStreamDataUni: ULong,
    val initialMaxStreamsBidi: ULong,
    val initialMaxStreamsUni: ULong,
    val ackDelayExponent: ULong = 3uL,
    val maxAckDelayMs: ULong = 25uL,
    val activeConnectionIdLimit: ULong = 2uL,
    val initialSourceConnectionId: ByteArray,
    val retrySourceConnectionId: ByteArray? = null,
    val maxDatagramFrameSize: ULong? = null
) {
    fun validate() {
        if (maxUdpPayloadSize < 1_200uL) {
            throw KQuicError.ProtocolViolation("max_udp_payload_size is smaller than 1200")
        }
        if (ackDelayExponent > 20uL) {
            throw KQuicError.ProtocolViolation("ack_delay_exponent exceeds 20")
        }
        if (maxAckDelayMs >= (1uL shl 14)) {
            throw KQuicError.ProtocolViolation("max_ack_delay exceeds 2^14 milliseconds")
        }
        if (activeConnectionIdLimit < 2uL) {
            throw KQuicError.ProtocolViolation("active_connection_id_limit is smaller than 2")
        }
        if (initialMaxStreamsBidi > (1uL shl 60) ||
            initialMaxStreamsUni > (1uL shl 60)
        ) {
            throw KQuicError.ProtocolViolation("Initial stream limit exceeds 2^60")
        }
        listOfNotNull(
            originalDestinationConnectionId,
            initialSourceConnectionId,
            retrySourceConnectionId
        ).forEach {
            if (it.size > 20) {
                throw KQuicError.ProtocolViolation("Transport parameter connection ID exceeds 20 bytes")
            }
        }
    }
}

private const val ORIGINAL_DESTINATION_CONNECTION_ID = 0x00uL
private const val MAX_IDLE_TIMEOUT = 0x01uL
private const val MAX_UDP_PAYLOAD_SIZE = 0x03uL
private const val INITIAL_MAX_DATA = 0x04uL
private const val INITIAL_MAX_STREAM_DATA_BIDI_LOCAL = 0x05uL
private const val INITIAL_MAX_STREAM_DATA_BIDI_REMOTE = 0x06uL
private const val INITIAL_MAX_STREAM_DATA_UNI = 0x07uL
private const val INITIAL_MAX_STREAMS_BIDI = 0x08uL
private const val INITIAL_MAX_STREAMS_UNI = 0x09uL
private const val ACK_DELAY_EXPONENT = 0x0auL
private const val MAX_ACK_DELAY = 0x0buL
private const val ACTIVE_CONNECTION_ID_LIMIT = 0x0euL
private const val INITIAL_SOURCE_CONNECTION_ID = 0x0fuL
private const val RETRY_SOURCE_CONNECTION_ID = 0x10uL
private const val MAX_DATAGRAM_FRAME_SIZE = 0x20uL
