package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError

internal data class Tls13CertificateVerify(val signature: ByteArray)

internal object Tls13AuthenticationCodec {
    fun encodeCertificateRequest(): TlsHandshakeMessage {
        val signatureAlgorithms = writer {
            u16(13)
            vector16(writer {
                u16(2)
                u16(ECDSA_SECP256R1_SHA256)
            })
        }
        return TlsHandshakeMessage(
            TlsHandshakeType.CERTIFICATE_REQUEST,
            writer {
                u8(0)
                vector16(signatureAlgorithms)
            }
        )
    }

    fun decodeCertificateRequest(message: TlsHandshakeMessage) {
        requireType(message, TlsHandshakeType.CERTIFICATE_REQUEST)
        val reader = AuthenticationReader(message.body)
        if (reader.vector8(MAX_CERTIFICATE_CONTEXT).isNotEmpty()) {
            fail("Post-handshake certificate context is not supported")
        }
        val extensions = AuthenticationReader(reader.vector16(MAX_AUTH_VECTOR))
        var supported = false
        while (extensions.remaining > 0) {
            val type = extensions.u16()
            val value = AuthenticationReader(extensions.vector16(MAX_AUTH_VECTOR))
            if (type == 13) {
                val algorithms = AuthenticationReader(value.vector16(MAX_AUTH_VECTOR))
                value.requireEnd("signature_algorithms")
                while (algorithms.remaining > 0) {
                    if (algorithms.u16() == ECDSA_SECP256R1_SHA256) supported = true
                }
            }
        }
        reader.requireEnd("CertificateRequest")
        if (!supported) fail("CertificateRequest does not allow ECDSA P-256/SHA-256")
    }

    fun encodeCertificate(certificateChainDer: List<ByteArray>): TlsHandshakeMessage {
        if (certificateChainDer.isEmpty()) fail("TLS certificate chain is empty")
        val certificateList = writer {
            certificateChainDer.forEach { certificate ->
                if (certificate.isEmpty() || certificate.size > MAX_CERTIFICATE_SIZE) {
                    fail("TLS certificate exceeds configured limit")
                }
                vector24(certificate)
                u16(0)
            }
        }
        return TlsHandshakeMessage(
            TlsHandshakeType.CERTIFICATE,
            writer {
                u8(0)
                vector24(certificateList)
            }
        )
    }

    fun decodeCertificate(message: TlsHandshakeMessage): List<ByteArray> {
        requireType(message, TlsHandshakeType.CERTIFICATE)
        val reader = AuthenticationReader(message.body)
        if (reader.vector8(MAX_CERTIFICATE_CONTEXT).isNotEmpty()) {
            fail("Unexpected TLS certificate request context")
        }
        val list = AuthenticationReader(reader.vector24(MAX_CERTIFICATE_CHAIN_SIZE))
        reader.requireEnd("Certificate")
        val certificates = mutableListOf<ByteArray>()
        while (list.remaining > 0) {
            certificates += list.vector24(MAX_CERTIFICATE_SIZE)
            list.vector16(MAX_AUTH_VECTOR)
            if (certificates.size > MAX_CERTIFICATE_CHAIN_LENGTH) {
                fail("TLS certificate chain contains too many certificates")
            }
        }
        if (certificates.isEmpty()) fail("Peer sent an empty TLS certificate chain")
        return certificates
    }

    fun encodeCertificateVerify(signature: ByteArray): TlsHandshakeMessage {
        if (signature.isEmpty()) fail("CertificateVerify signature is empty")
        return TlsHandshakeMessage(
            TlsHandshakeType.CERTIFICATE_VERIFY,
            writer {
                u16(ECDSA_SECP256R1_SHA256)
                vector16(signature)
            }
        )
    }

    fun decodeCertificateVerify(message: TlsHandshakeMessage): Tls13CertificateVerify {
        requireType(message, TlsHandshakeType.CERTIFICATE_VERIFY)
        val reader = AuthenticationReader(message.body)
        if (reader.u16() != ECDSA_SECP256R1_SHA256) {
            fail("Unsupported CertificateVerify signature algorithm")
        }
        val signature = reader.vector16(MAX_AUTH_VECTOR)
        reader.requireEnd("CertificateVerify")
        if (signature.isEmpty()) fail("CertificateVerify signature is empty")
        return Tls13CertificateVerify(signature)
    }

    fun certificateVerifyInput(
        server: Boolean,
        transcriptHash: ByteArray
    ): ByteArray {
        if (transcriptHash.size != SHA256_LENGTH) {
            fail("CertificateVerify transcript hash must use SHA-256")
        }
        val context = if (server) {
            "TLS 1.3, server CertificateVerify"
        } else {
            "TLS 1.3, client CertificateVerify"
        }
        return ByteArray(64) { 0x20 } +
            context.encodeToByteArray() +
            byteArrayOf(0) +
            transcriptHash
    }

    fun encodeFinished(verifyData: ByteArray): TlsHandshakeMessage {
        if (verifyData.size != SHA256_LENGTH) fail("TLS Finished must contain 32 bytes")
        return TlsHandshakeMessage(TlsHandshakeType.FINISHED, verifyData.copyOf())
    }

    fun decodeFinished(message: TlsHandshakeMessage): ByteArray {
        requireType(message, TlsHandshakeType.FINISHED)
        if (message.body.size != SHA256_LENGTH) fail("TLS Finished must contain 32 bytes")
        return message.body.copyOf()
    }

    private fun requireType(message: TlsHandshakeMessage, expected: TlsHandshakeType) {
        if (message.type != expected) fail("Expected TLS $expected, received ${message.type}")
    }
}

private class AuthenticationWriter {
    private val output = mutableListOf<Byte>()
    fun u8(value: Int) {
        require(value in 0..255)
        output += value.toByte()
    }
    fun u16(value: Int) {
        require(value in 0..65_535)
        output += (value ushr 8).toByte()
        output += value.toByte()
    }
    fun u24(value: Int) {
        require(value in 0..0x00ff_ffff)
        output += (value ushr 16).toByte()
        output += (value ushr 8).toByte()
        output += value.toByte()
    }
    fun raw(value: ByteArray) {
        output += value.toList()
    }
    fun vector16(value: ByteArray) {
        u16(value.size)
        raw(value)
    }
    fun vector24(value: ByteArray) {
        u24(value.size)
        raw(value)
    }
    fun build(): ByteArray = output.toByteArray()
}

private class AuthenticationReader(private val input: ByteArray) {
    private var position = 0
    val remaining: Int get() = input.size - position
    fun u8(): Int {
        if (remaining < 1) fail("Truncated TLS authentication message")
        return input[position++].toInt() and 0xff
    }
    fun u16(): Int = (u8() shl 8) or u8()
    fun u24(): Int = (u8() shl 16) or (u8() shl 8) or u8()
    fun bytes(size: Int): ByteArray {
        if (size < 0 || size > remaining) fail("Truncated TLS authentication vector")
        return input.copyOfRange(position, position + size).also { position += size }
    }
    fun vector8(maximum: Int): ByteArray = bytes(u8().checkedMaximum(maximum))
    fun vector16(maximum: Int): ByteArray = bytes(u16().checkedMaximum(maximum))
    fun vector24(maximum: Int): ByteArray = bytes(u24().checkedMaximum(maximum))
    fun requireEnd(field: String) {
        if (remaining != 0) fail("$field contains trailing bytes")
    }
    private fun Int.checkedMaximum(maximum: Int): Int {
        if (this > maximum) fail("TLS authentication vector exceeds configured limit")
        return this
    }
}

private inline fun writer(block: AuthenticationWriter.() -> Unit): ByteArray =
    AuthenticationWriter().apply(block).build()

private fun fail(message: String): Nothing = throw KQuicError.ProtocolViolation(message)

private const val ECDSA_SECP256R1_SHA256 = 0x0403
private const val SHA256_LENGTH = 32
private const val MAX_CERTIFICATE_CONTEXT = 255
private const val MAX_AUTH_VECTOR = 65_535
private const val MAX_CERTIFICATE_SIZE = 262_144
private const val MAX_CERTIFICATE_CHAIN_SIZE = 1_048_576
private const val MAX_CERTIFICATE_CHAIN_LENGTH = 8
