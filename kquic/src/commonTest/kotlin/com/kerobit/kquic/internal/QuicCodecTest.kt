package com.kerobit.kquic.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

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

    @Test
    fun flow_control_and_shutdown_frames_roundTrip() {
        val frames = listOf(
            QuicFrame.ResetStream(4uL, 7uL, 128uL),
            QuicFrame.StopSending(4uL, 7uL),
            QuicFrame.MaxData(1_000uL),
            QuicFrame.MaxStreamData(4uL, 500uL),
            QuicFrame.MaxStreams(bidirectional = true, maximumStreams = 12uL),
            QuicFrame.DataBlocked(1_000uL),
            QuicFrame.StreamDataBlocked(4uL, 500uL),
            QuicFrame.StreamsBlocked(bidirectional = false, maximumStreams = 3uL),
            QuicFrame.RetireConnectionId(0uL),
            QuicFrame.HandshakeDone
        )
        val payload = frames.fold(ByteArray(0)) { result, frame ->
            result + QuicFrames.encode(frame)
        }
        assertEquals(frames, QuicFrames.decodeAll(payload))

        val newId = QuicFrame.NewConnectionId(
            sequence = 1uL,
            retirePriorTo = 0uL,
            connectionId = byteArrayOf(1, 2, 3),
            statelessResetToken = ByteArray(16) { it.toByte() }
        )
        val decodedNewId = QuicFrames.decodeAll(
            QuicFrames.encode(newId)
        ).single() as QuicFrame.NewConnectionId
        assertEquals(newId.sequence, decodedNewId.sequence)
        assertContentEquals(newId.connectionId, decodedNewId.connectionId)
        assertContentEquals(newId.statelessResetToken, decodedNewId.statelessResetToken)
    }

    @Test
    fun offset_reassembly_handles_gaps_duplicates_and_fin() {
        val reassembler = QuicOffsetReassembler(maxBufferedBytes = 64, maxOffset = 64uL)
        assertTrue(reassembler.add(3uL, byteArrayOf(4, 5, 6), fin = true).isEmpty())
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), reassembler.add(
            0uL,
            byteArrayOf(1, 2, 3, 4)
        ))
        assertTrue(reassembler.complete)
        assertTrue(reassembler.add(0uL, byteArrayOf(1, 2, 3)).isEmpty())
    }

    @Test
    fun offset_reassembly_rejects_conflicting_overlap_and_memory_exhaustion() {
        val conflict = QuicOffsetReassembler(maxBufferedBytes = 64, maxOffset = 64uL)
        conflict.add(4uL, byteArrayOf(1, 2))
        assertFailsWith<IllegalStateException> {
            conflict.add(5uL, byteArrayOf(9))
        }

        val bounded = QuicOffsetReassembler(maxBufferedBytes = 4, maxOffset = 64uL)
        assertFailsWith<IllegalStateException> {
            bounded.add(8uL, ByteArray(5))
        }
    }

    @Test
    fun parsers_reject_unknown_frames_and_allow_an_explicit_flow_control_window_update() {
        assertFailsWith<IllegalStateException> {
            QuicFrames.decodeAll(byteArrayOf(0x3f))
        }

        val reassembler = QuicOffsetReassembler(maxBufferedBytes = 64, maxOffset = 4uL)
        assertContentEquals(byteArrayOf(1, 2, 3, 4), reassembler.add(
            0uL,
            byteArrayOf(1, 2, 3, 4)
        ))
        reassembler.updateMaximumOffset(8uL)
        assertContentEquals(byteArrayOf(5, 6, 7, 8), reassembler.add(
            4uL,
            byteArrayOf(5, 6, 7, 8)
        ))
    }

    @Test
    fun typed_transport_parameters_roundTrip_datagram_and_flow_control_limits() {
        val values = QuicTransportParameterValues(
            initialMaxData = 1_000_000uL,
            initialMaxStreamDataBidiLocal = 100_000uL,
            initialMaxStreamDataBidiRemote = 100_000uL,
            initialMaxStreamDataUni = 50_000uL,
            initialMaxStreamsBidi = 64uL,
            initialMaxStreamsUni = 8uL,
            initialSourceConnectionId = byteArrayOf(1, 2, 3),
            maxDatagramFrameSize = 1_200uL
        )
        val decoded = QuicTransportParameters.decode(
            QuicTransportParameters.encode(values)
        ).decoded()
        assertEquals(values.initialMaxData, decoded.initialMaxData)
        assertEquals(values.initialMaxStreamsBidi, decoded.initialMaxStreamsBidi)
        assertEquals(values.maxDatagramFrameSize, decoded.maxDatagramFrameSize)
        assertContentEquals(values.initialSourceConnectionId, decoded.initialSourceConnectionId)
    }

    @Test
    fun tls_handshake_framer_accepts_fragmented_crypto_input() {
        val first = TlsHandshakeMessage(
            TlsHandshakeType.CLIENT_HELLO,
            byteArrayOf(1, 2, 3)
        ).encode()
        val second = TlsHandshakeMessage(
            TlsHandshakeType.SERVER_HELLO,
            byteArrayOf(4, 5)
        ).encode()
        val framer = TlsHandshakeFramer()
        assertTrue(framer.feed((first + second).copyOfRange(0, 5)).isEmpty())
        val decoded = framer.feed((first + second).copyOfRange(5, first.size + second.size))
        assertEquals(listOf(TlsHandshakeType.CLIENT_HELLO, TlsHandshakeType.SERVER_HELLO),
            decoded.map(TlsHandshakeMessage::type))
        assertContentEquals(byteArrayOf(1, 2, 3), decoded[0].body)
        assertContentEquals(byteArrayOf(4, 5), decoded[1].body)
    }

    @Test
    fun stream_manager_allocates_ids_and_reassembles_remote_streams() {
        val manager = QuicStreamManager(
            role = QuicEndpointRole.CLIENT,
            localMaxBidirectionalStreams = 4uL,
            localMaxUnidirectionalStreams = 2uL,
            localMaxStreamData = 64uL,
            maximumBufferedBytesPerStream = 64
        )
        manager.updatePeerMaxStreams(bidirectional = true, maximum = 2uL)
        assertEquals(0uL, manager.openBidirectional())
        assertEquals(4uL, manager.openBidirectional())
        assertFailsWith<IllegalStateException> { manager.openBidirectional() }

        assertTrue(manager.receive(
            QuicFrame.Stream(1uL, 3uL, true, byteArrayOf(4, 5))
        ).bytes.isEmpty())
        val received = manager.receive(
            QuicFrame.Stream(1uL, 0uL, false, byteArrayOf(1, 2, 3))
        )
        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), received.bytes)
        assertTrue(received.complete)
    }

    @Test
    fun stream_manager_rejects_peer_data_on_local_unidirectional_stream() {
        val manager = QuicStreamManager(
            role = QuicEndpointRole.CLIENT,
            localMaxBidirectionalStreams = 1uL,
            localMaxUnidirectionalStreams = 1uL,
            localMaxStreamData = 64uL,
            maximumBufferedBytesPerStream = 64
        )
        assertFailsWith<IllegalStateException> {
            manager.receive(QuicFrame.Stream(2uL, 0uL, false, byteArrayOf(1)))
        }
    }

    @Test
    fun initial_padding_and_server_anti_amplification_are_enforced() {
        assertEquals(1_200, QuicInitialDatagrams.pad(ByteArray(100)).size)
        val guard = QuicAmplificationGuard(server = true)
        guard.onDatagramReceived(1_200)
        assertTrue(guard.canSend(3_600))
        guard.onDatagramSent(3_600)
        assertTrue(!guard.canSend(1))
        guard.validateAddress()
        assertTrue(guard.canSend(64_000))
    }

    @Test
    fun strict_tls_hello_profile_roundTrips_p256_alpn_and_quic_parameters() {
        val publicKey = ByteArray(65) { it.toByte() }.also { it[0] = 0x04 }
        val parameters = byteArrayOf(1, 2, 3)
        val client = Tls13HelloCodec.decodeClientHello(
            Tls13HelloCodec.encodeClientHello(
                Tls13ClientHello(ByteArray(32) { 7 }, publicKey, parameters)
            )
        )
        assertContentEquals(publicKey, client.keyShare)
        assertContentEquals(parameters, client.transportParameters)

        val server = Tls13HelloCodec.decodeServerHello(
            Tls13HelloCodec.encodeServerHello(
                Tls13ServerHello(ByteArray(32) { 9 }, publicKey)
            )
        )
        assertContentEquals(publicKey, server.keyShare)

        val encryptedExtensions = Tls13HelloCodec.decodeEncryptedExtensions(
            Tls13HelloCodec.encodeEncryptedExtensions(
                Tls13EncryptedExtensions(parameters)
            )
        )
        assertContentEquals(parameters, encryptedExtensions.transportParameters)
    }

    @Test
    fun strict_tls_profile_rejects_wrong_alpn() {
        val publicKey = ByteArray(65).also { it[0] = 0x04 }
        val hello = Tls13HelloCodec.encodeClientHello(
            Tls13ClientHello(ByteArray(32), publicKey, ByteArray(0))
        )
        val modified = hello.body.copyOf()
        val alpnOffset = modified.indexOfSlice("kquic/1".encodeToByteArray())
        modified[alpnOffset] = 'x'.code.toByte()
        assertFailsWith<IllegalStateException> {
            Tls13HelloCodec.decodeClientHello(hello.copy(body = modified))
        }
    }

    @Test
    fun tls_mutual_authentication_messages_are_bounded_and_roundTrip() {
        Tls13AuthenticationCodec.decodeCertificateRequest(
            Tls13AuthenticationCodec.encodeCertificateRequest()
        )
        val chain = listOf(byteArrayOf(1, 2, 3), byteArrayOf(4, 5))
        val decodedChain = Tls13AuthenticationCodec.decodeCertificate(
            Tls13AuthenticationCodec.encodeCertificate(chain)
        )
        assertContentEquals(chain[0], decodedChain[0])
        assertContentEquals(chain[1], decodedChain[1])

        val signature = byteArrayOf(0x30, 1, 2)
        assertContentEquals(
            signature,
            Tls13AuthenticationCodec.decodeCertificateVerify(
                Tls13AuthenticationCodec.encodeCertificateVerify(signature)
            ).signature
        )
        val verifyInput = Tls13AuthenticationCodec.certificateVerifyInput(
            server = true,
            transcriptHash = ByteArray(32) { 9 }
        )
        assertEquals(64 + "TLS 1.3, server CertificateVerify".length + 1 + 32,
            verifyInput.size)

        val finished = ByteArray(32) { 7 }
        assertContentEquals(
            finished,
            Tls13AuthenticationCodec.decodeFinished(
                Tls13AuthenticationCodec.encodeFinished(finished)
            )
        )
    }

    private fun ByteArray.indexOfSlice(value: ByteArray): Int {
        for (index in 0..size - value.size) {
            if (copyOfRange(index, index + value.size).contentEquals(value)) return index
        }
        error("Slice not found")
    }
}
