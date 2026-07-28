package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicIdentity
import com.kerobit.kquic.KQuicRemoteIdentity

/**
 * TLS/CRYPTO state owned by one QUIC connection. It has no socket or timer dependency.
 */
internal class QuicHandshake(
    private val endpointRole: QuicEndpointRole,
    private val identity: KQuicIdentity,
    private val config: KQuicConfig,
    private val handshakeCrypto: Tls13HandshakeCrypto,
    private val localConnectionId: ByteArray
) {
    private var session: CommonQuicTlsSession? = null
    private val cryptoReceive = mapOf(
        QuicEncryptionLevel.INITIAL to QuicOffsetReassembler(
            config.limits.maxBufferedCryptoBytes,
            config.limits.maxBufferedCryptoBytes.toULong()
        ),
        QuicEncryptionLevel.HANDSHAKE to QuicOffsetReassembler(
            config.limits.maxBufferedCryptoBytes,
            config.limits.maxBufferedCryptoBytes.toULong()
        )
    )
    private val cryptoSendOffset = mutableMapOf(
        QuicEncryptionLevel.INITIAL to 0uL,
        QuicEncryptionLevel.HANDSHAKE to 0uL
    )

    var remoteIdentity: KQuicRemoteIdentity? = null
        private set
    var remoteParameters: QuicTransportParameterValues? = null
        private set
    var complete: Boolean = false
        private set
    val initialized: Boolean get() = session != null

    fun initialize(originalDestinationConnectionId: ByteArray?) {
        check(session == null) { "QUIC TLS session is already initialized" }
        val parameters = QuicTransportParameterValues(
            originalDestinationConnectionId =
                if (endpointRole == QuicEndpointRole.SERVER) {
                    originalDestinationConnectionId
                } else {
                    null
                },
            maxIdleTimeoutMs = config.idleTimeoutMs.toULong(),
            maxUdpPayloadSize = QUIC_MAX_UDP_DATAGRAM_SIZE.toULong(),
            initialMaxData = config.limits.maxConnectionData.toULong(),
            initialMaxStreamDataBidiLocal = config.limits.maxStreamData.toULong(),
            initialMaxStreamDataBidiRemote = config.limits.maxStreamData.toULong(),
            initialMaxStreamDataUni = config.limits.maxStreamData.toULong(),
            initialMaxStreamsBidi = config.limits.maxBidirectionalStreams.toULong(),
            initialMaxStreamsUni = config.limits.maxUnidirectionalStreams.toULong(),
            initialSourceConnectionId = localConnectionId,
            maxDatagramFrameSize = QUIC_MAX_FRAME_PAYLOAD.toULong()
        )
        session = CommonQuicTlsSession(endpointRole, identity, parameters, handshakeCrypto)
    }

    suspend fun start(): List<QuicTlsEvent> =
        checkNotNull(session) { "QUIC TLS session is not initialized" }.start()

    suspend fun receive(
        level: QuicEncryptionLevel,
        frame: QuicFrame.Crypto
    ): List<QuicTlsEvent> {
        val reassembler = cryptoReceive[level]
            ?: throw KQuicError.ProtocolViolation("CRYPTO at invalid level")
        val contiguous = reassembler.add(frame.offset, frame.data)
        return if (contiguous.isEmpty()) {
            emptyList()
        } else {
            checkNotNull(session).receive(level, contiguous)
        }
    }

    fun outboundCryptoFrames(
        level: QuicEncryptionLevel,
        bytes: ByteArray,
        maximumPayload: Int
    ): List<QuicFrame.Crypto> {
        val frames = mutableListOf<QuicFrame.Crypto>()
        var position = 0
        var offset = checkNotNull(cryptoSendOffset[level]) {
            "TLS emitted CRYPTO at invalid level"
        }
        while (position < bytes.size) {
            val end = minOf(position + maximumPayload, bytes.size)
            val chunk = bytes.copyOfRange(position, end)
            frames += QuicFrame.Crypto(offset, chunk)
            offset += chunk.size.toULong()
            position = end
        }
        cryptoSendOffset[level] = offset
        return frames
    }

    fun record(event: QuicTlsEvent) {
        when (event) {
            is QuicTlsEvent.RemoteIdentityAvailable -> remoteIdentity = event.identity
            is QuicTlsEvent.TransportParametersAvailable -> remoteParameters = event.parameters
            QuicTlsEvent.HandshakeComplete -> complete = true
            else -> Unit
        }
    }

    fun close() {
        session?.close()
    }
}

internal const val QUIC_MAX_UDP_DATAGRAM_SIZE = 1_200
internal const val QUIC_MAX_FRAME_PAYLOAD = 1_150
