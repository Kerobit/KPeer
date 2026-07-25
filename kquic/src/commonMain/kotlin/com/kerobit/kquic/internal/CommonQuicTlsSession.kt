package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicIdentity
import com.kerobit.kquic.KQuicRemoteIdentity
import com.kerobit.kquic.KQuicSignatureAlgorithm

/**
 * Common TLS 1.3 handshake machine for the single KQuic cipher/profile.
 *
 * It consumes already reassembled CRYPTO bytes and emits protocol bytes and key transitions. All
 * asymmetric and symmetric primitives are delegated to [Tls13HandshakeCrypto].
 */
internal class CommonQuicTlsSession(
    private val role: QuicEndpointRole,
    private val identity: KQuicIdentity,
    localTransportParameters: QuicTransportParameterValues,
    private val crypto: Tls13HandshakeCrypto
) : QuicTlsSession {
    private val keySchedule = Tls13KeySchedule(crypto.primitives)
    private val localTransportParameters = QuicTransportParameters.encode(localTransportParameters)
    private val initialFramer = TlsHandshakeFramer()
    private val handshakeFramer = TlsHandshakeFramer()
    private val keyAgreement = crypto.createP256KeyAgreement()
    private var transcript = ByteArray(0)
    private var handshakeSecrets: Tls13HandshakeSecrets? = null
    private var peerCertificates: List<ByteArray>? = null
    private var certificateRequested = false
    private var remoteParametersEmitted = false
    private var state = if (role == QuicEndpointRole.CLIENT) {
        State.CLIENT_NEW
    } else {
        State.SERVER_WAIT_CLIENT_HELLO
    }

    override suspend fun start(): List<QuicTlsEvent> {
        if (state != State.CLIENT_NEW) return emptyList()
        val clientHello = Tls13HelloCodec.encodeClientHello(
            Tls13ClientHello(
                random = crypto.secureRandom(32),
                keyShare = keyAgreement.publicKey,
                transportParameters = localTransportParameters
            )
        )
        append(clientHello)
        state = State.CLIENT_WAIT_SERVER_HELLO
        return listOf(QuicTlsEvent.CryptoData(QuicEncryptionLevel.INITIAL, clientHello.encode()))
    }

    override suspend fun receive(
        level: QuicEncryptionLevel,
        cryptoBytes: ByteArray
    ): List<QuicTlsEvent> {
        val messages = when (level) {
            QuicEncryptionLevel.INITIAL -> initialFramer.feed(cryptoBytes)
            QuicEncryptionLevel.HANDSHAKE -> handshakeFramer.feed(cryptoBytes)
            QuicEncryptionLevel.ONE_RTT ->
                throw KQuicError.ProtocolViolation("Post-handshake TLS messages are not supported")
        }
        val events = mutableListOf<QuicTlsEvent>()
        for (message in messages) {
            if (role == QuicEndpointRole.CLIENT) {
                processClient(message, level, events)
            } else {
                processServer(message, level, events)
            }
        }
        return events
    }

    private suspend fun processClient(
        message: TlsHandshakeMessage,
        level: QuicEncryptionLevel,
        events: MutableList<QuicTlsEvent>
    ) {
        when (state) {
            State.CLIENT_WAIT_SERVER_HELLO -> {
                requireLevel(level, QuicEncryptionLevel.INITIAL)
                val serverHello = Tls13HelloCodec.decodeServerHello(message)
                append(message)
                val secrets = keySchedule.handshakeSecrets(
                    keyAgreement.deriveSharedSecret(serverHello.keyShare),
                    transcript
                )
                handshakeSecrets = secrets
                events += QuicTlsEvent.SecretsAvailable(
                    QuicEncryptionLevel.HANDSHAKE,
                    QuicTrafficSecrets(
                        read = secrets.serverHandshakeTrafficSecret,
                        write = secrets.clientHandshakeTrafficSecret
                    )
                )
                state = State.CLIENT_WAIT_ENCRYPTED_EXTENSIONS
            }
            State.CLIENT_WAIT_ENCRYPTED_EXTENSIONS -> {
                requireLevel(level, QuicEncryptionLevel.HANDSHAKE)
                val encrypted = Tls13HelloCodec.decodeEncryptedExtensions(message)
                append(message)
                emitRemoteParameters(encrypted.transportParameters, events)
                state = State.CLIENT_WAIT_CERTIFICATE_REQUEST
            }
            State.CLIENT_WAIT_CERTIFICATE_REQUEST -> {
                requireLevel(level, QuicEncryptionLevel.HANDSHAKE)
                Tls13AuthenticationCodec.decodeCertificateRequest(message)
                certificateRequested = true
                append(message)
                state = State.CLIENT_WAIT_CERTIFICATE
            }
            State.CLIENT_WAIT_CERTIFICATE -> {
                requireLevel(level, QuicEncryptionLevel.HANDSHAKE)
                peerCertificates = Tls13AuthenticationCodec.decodeCertificate(message)
                append(message)
                state = State.CLIENT_WAIT_CERTIFICATE_VERIFY
            }
            State.CLIENT_WAIT_CERTIFICATE_VERIFY -> {
                requireLevel(level, QuicEncryptionLevel.HANDSHAKE)
                verifyCertificateVerify(message, server = true)
                append(message)
                state = State.CLIENT_WAIT_FINISHED
            }
            State.CLIENT_WAIT_FINISHED -> {
                requireLevel(level, QuicEncryptionLevel.HANDSHAKE)
                verifyFinished(
                    message,
                    checkNotNull(handshakeSecrets).serverHandshakeTrafficSecret
                )
                append(message)
                val application = keySchedule.applicationSecrets(
                    checkNotNull(handshakeSecrets).handshakeSecret,
                    transcript
                )
                events += remoteIdentityEvent()
                events += clientAuthenticationFlight()
                events += QuicTlsEvent.SecretsAvailable(
                    QuicEncryptionLevel.ONE_RTT,
                    QuicTrafficSecrets(
                        read = application.serverApplicationTrafficSecret,
                        write = application.clientApplicationTrafficSecret
                    )
                )
                events += QuicTlsEvent.HandshakeComplete
                state = State.COMPLETE
            }
            else -> invalidState(message)
        }
    }

    private suspend fun processServer(
        message: TlsHandshakeMessage,
        level: QuicEncryptionLevel,
        events: MutableList<QuicTlsEvent>
    ) {
        when (state) {
            State.SERVER_WAIT_CLIENT_HELLO -> {
                requireLevel(level, QuicEncryptionLevel.INITIAL)
                val clientHello = Tls13HelloCodec.decodeClientHello(message)
                append(message)
                emitRemoteParameters(clientHello.transportParameters, events)
                val serverHello = Tls13HelloCodec.encodeServerHello(
                    Tls13ServerHello(
                        random = crypto.secureRandom(32),
                        keyShare = keyAgreement.publicKey
                    )
                )
                append(serverHello)
                val secrets = keySchedule.handshakeSecrets(
                    keyAgreement.deriveSharedSecret(clientHello.keyShare),
                    transcript
                )
                handshakeSecrets = secrets
                events += QuicTlsEvent.CryptoData(
                    QuicEncryptionLevel.INITIAL,
                    serverHello.encode()
                )
                events += QuicTlsEvent.SecretsAvailable(
                    QuicEncryptionLevel.HANDSHAKE,
                    QuicTrafficSecrets(
                        read = secrets.clientHandshakeTrafficSecret,
                        write = secrets.serverHandshakeTrafficSecret
                    )
                )
                val flight = serverAuthenticationFlight()
                events += QuicTlsEvent.CryptoData(QuicEncryptionLevel.HANDSHAKE, flight)
                val application = keySchedule.applicationSecrets(secrets.handshakeSecret, transcript)
                events += QuicTlsEvent.SecretsAvailable(
                    QuicEncryptionLevel.ONE_RTT,
                    QuicTrafficSecrets(
                        read = application.clientApplicationTrafficSecret,
                        write = application.serverApplicationTrafficSecret
                    )
                )
                state = State.SERVER_WAIT_CERTIFICATE
            }
            State.SERVER_WAIT_CERTIFICATE -> {
                requireLevel(level, QuicEncryptionLevel.HANDSHAKE)
                peerCertificates = Tls13AuthenticationCodec.decodeCertificate(message)
                append(message)
                state = State.SERVER_WAIT_CERTIFICATE_VERIFY
            }
            State.SERVER_WAIT_CERTIFICATE_VERIFY -> {
                requireLevel(level, QuicEncryptionLevel.HANDSHAKE)
                verifyCertificateVerify(message, server = false)
                append(message)
                state = State.SERVER_WAIT_FINISHED
            }
            State.SERVER_WAIT_FINISHED -> {
                requireLevel(level, QuicEncryptionLevel.HANDSHAKE)
                verifyFinished(
                    message,
                    checkNotNull(handshakeSecrets).clientHandshakeTrafficSecret
                )
                append(message)
                events += remoteIdentityEvent()
                events += QuicTlsEvent.HandshakeComplete
                state = State.COMPLETE
            }
            else -> invalidState(message)
        }
    }

    private suspend fun serverAuthenticationFlight(): ByteArray {
        val messages = mutableListOf<TlsHandshakeMessage>()
        messages += Tls13HelloCodec.encodeEncryptedExtensions(
            Tls13EncryptedExtensions(localTransportParameters)
        ).also(::append)
        messages += Tls13AuthenticationCodec.encodeCertificateRequest().also(::append)
        certificateRequested = true
        messages += Tls13AuthenticationCodec.encodeCertificate(
            identity.certificateChainDer
        ).also(::append)
        val signature = identity.signer.sign(
            KQuicSignatureAlgorithm.ECDSA_SECP256R1_SHA256,
            Tls13AuthenticationCodec.certificateVerifyInput(
                server = true,
                transcriptHash = crypto.primitives.sha256(transcript)
            )
        )
        messages += Tls13AuthenticationCodec.encodeCertificateVerify(signature).also(::append)
        val finished = Tls13AuthenticationCodec.encodeFinished(
            keySchedule.finishedVerifyData(
                checkNotNull(handshakeSecrets).serverHandshakeTrafficSecret,
                transcript
            )
        )
        messages += finished.also(::append)
        return concatenate(messages)
    }

    private suspend fun clientAuthenticationFlight(): QuicTlsEvent.CryptoData {
        if (!certificateRequested) {
            throw KQuicError.ProtocolViolation("Server did not request mutual TLS authentication")
        }
        val messages = mutableListOf<TlsHandshakeMessage>()
        messages += Tls13AuthenticationCodec.encodeCertificate(
            identity.certificateChainDer
        ).also(::append)
        val signature = identity.signer.sign(
            KQuicSignatureAlgorithm.ECDSA_SECP256R1_SHA256,
            Tls13AuthenticationCodec.certificateVerifyInput(
                server = false,
                transcriptHash = crypto.primitives.sha256(transcript)
            )
        )
        messages += Tls13AuthenticationCodec.encodeCertificateVerify(signature).also(::append)
        val finished = Tls13AuthenticationCodec.encodeFinished(
            keySchedule.finishedVerifyData(
                checkNotNull(handshakeSecrets).clientHandshakeTrafficSecret,
                transcript
            )
        )
        messages += finished.also(::append)
        return QuicTlsEvent.CryptoData(
            QuicEncryptionLevel.HANDSHAKE,
            concatenate(messages)
        )
    }

    private fun verifyCertificateVerify(message: TlsHandshakeMessage, server: Boolean) {
        val certificates = peerCertificates
            ?: throw KQuicError.ProtocolViolation("CertificateVerify arrived before Certificate")
        val signature = Tls13AuthenticationCodec.decodeCertificateVerify(message).signature
        val spki = extractSubjectPublicKeyInfo(certificates.first())
        val input = Tls13AuthenticationCodec.certificateVerifyInput(
            server,
            crypto.primitives.sha256(transcript)
        )
        if (!crypto.verifyEcdsaP256Sha256(spki, input, signature)) {
            throw KQuicError.AuthenticationFailed("TLS CertificateVerify signature is invalid")
        }
    }

    private fun verifyFinished(message: TlsHandshakeMessage, baseSecret: ByteArray) {
        val received = Tls13AuthenticationCodec.decodeFinished(message)
        val expected = keySchedule.finishedVerifyData(baseSecret, transcript)
        if (!constantTimeEquals(received, expected)) {
            throw KQuicError.AuthenticationFailed("TLS Finished verification failed")
        }
    }

    private fun remoteIdentityEvent(): QuicTlsEvent.RemoteIdentityAvailable {
        val certificates = checkNotNull(peerCertificates)
        val spki = extractSubjectPublicKeyInfo(certificates.first())
        return QuicTlsEvent.RemoteIdentityAvailable(
            KQuicRemoteIdentity(
                certificateChainDer = certificates,
                publicKeySha256 = crypto.primitives.sha256(spki).toHex()
            )
        )
    }

    private fun emitRemoteParameters(
        encoded: ByteArray,
        events: MutableList<QuicTlsEvent>
    ) {
        if (remoteParametersEmitted) {
            throw KQuicError.ProtocolViolation("Duplicate remote QUIC transport parameters")
        }
        events += QuicTlsEvent.TransportParametersAvailable(
            QuicTransportParameters.decode(encoded).decoded()
        )
        remoteParametersEmitted = true
    }

    private fun append(message: TlsHandshakeMessage) {
        transcript += message.encode()
    }

    private fun concatenate(messages: List<TlsHandshakeMessage>): ByteArray =
        messages.fold(ByteArray(0)) { bytes, message -> bytes + message.encode() }

    private fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean {
        if (first.size != second.size) return false
        var difference = 0
        first.indices.forEach { index ->
            difference = difference or (first[index].toInt() xor second[index].toInt())
        }
        return difference == 0
    }

    private fun requireLevel(actual: QuicEncryptionLevel, expected: QuicEncryptionLevel) {
        if (actual != expected) {
            throw KQuicError.ProtocolViolation(
                "TLS handshake message arrived at $actual; expected $expected"
            )
        }
    }

    private fun invalidState(message: TlsHandshakeMessage): Nothing =
        throw KQuicError.ProtocolViolation(
            "Unexpected ${message.type} in TLS state $state"
        )

    private enum class State {
        CLIENT_NEW,
        CLIENT_WAIT_SERVER_HELLO,
        CLIENT_WAIT_ENCRYPTED_EXTENSIONS,
        CLIENT_WAIT_CERTIFICATE_REQUEST,
        CLIENT_WAIT_CERTIFICATE,
        CLIENT_WAIT_CERTIFICATE_VERIFY,
        CLIENT_WAIT_FINISHED,
        SERVER_WAIT_CLIENT_HELLO,
        SERVER_WAIT_CERTIFICATE,
        SERVER_WAIT_CERTIFICATE_VERIFY,
        SERVER_WAIT_FINISHED,
        COMPLETE
    }
}
