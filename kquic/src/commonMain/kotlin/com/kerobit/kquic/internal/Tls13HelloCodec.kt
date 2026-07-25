package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError

internal data class Tls13ClientHello(
    val random: ByteArray,
    val keyShare: ByteArray,
    val transportParameters: ByteArray
)

internal data class Tls13ServerHello(
    val random: ByteArray,
    val keyShare: ByteArray
)

internal data class Tls13EncryptedExtensions(
    val transportParameters: ByteArray
)

/**
 * Strict codec for KQuic's deliberately narrow TLS profile.
 *
 * The codec accepts exactly TLS 1.3, AES-128-GCM/SHA-256, P-256, ECDSA P-256/SHA-256 and
 * `kquic/1`. PSK, 0-RTT and HelloRetryRequest are intentionally outside the profile.
 */
internal object Tls13HelloCodec {
    fun encodeClientHello(value: Tls13ClientHello): TlsHandshakeMessage {
        require(value.random.size == 32)
        require(value.keyShare.size == 65)
        val extensions = encodeExtensions(
            EXT_SUPPORTED_VERSIONS to bytes {
                u8(2)
                u16(TLS_1_3)
            },
            EXT_SUPPORTED_GROUPS to bytes {
                u16(2)
                u16(GROUP_SECP256R1)
            },
            EXT_SIGNATURE_ALGORITHMS to bytes {
                u16(2)
                u16(SIGNATURE_ECDSA_SECP256R1_SHA256)
            },
            EXT_KEY_SHARE to bytes {
                u16(2 + 2 + value.keyShare.size)
                u16(GROUP_SECP256R1)
                vector16(value.keyShare)
            },
            EXT_ALPN to alpnExtension(),
            EXT_QUIC_TRANSPORT_PARAMETERS to value.transportParameters
        )
        return TlsHandshakeMessage(
            TlsHandshakeType.CLIENT_HELLO,
            bytes {
                u16(TLS_LEGACY_VERSION)
                raw(value.random)
                u8(0)
                u16(2)
                u16(TLS_AES_128_GCM_SHA256)
                u8(1)
                u8(0)
                vector16(extensions)
            }
        )
    }

    fun decodeClientHello(message: TlsHandshakeMessage): Tls13ClientHello {
        if (message.type != TlsHandshakeType.CLIENT_HELLO) unexpected(message)
        val reader = TlsReader(message.body)
        reader.requireU16(TLS_LEGACY_VERSION, "ClientHello legacy_version")
        val random = reader.bytes(32)
        reader.vector8(MAX_LEGACY_SESSION_ID_SIZE)
        val cipherSuites = TlsReader(reader.vector16(MAX_TLS_VECTOR_SIZE))
        var cipherSupported = false
        while (cipherSuites.remaining > 0) {
            if (cipherSuites.u16() == TLS_AES_128_GCM_SHA256) cipherSupported = true
        }
        if (!cipherSupported) fail("ClientHello does not offer TLS_AES_128_GCM_SHA256")
        val compression = reader.vector8(255)
        if (!compression.contentEquals(byteArrayOf(0))) {
            fail("ClientHello legacy compression is invalid")
        }
        val extensions = decodeExtensions(reader.vector16(MAX_TLS_VECTOR_SIZE))
        reader.requireEnd("ClientHello")
        requireClientProfile(extensions)
        val keyShareReader = TlsReader(required(extensions, EXT_KEY_SHARE, "key_share"))
        val shares = TlsReader(keyShareReader.vector16(MAX_TLS_VECTOR_SIZE))
        keyShareReader.requireEnd("client key_share")
        var p256: ByteArray? = null
        while (shares.remaining > 0) {
            val group = shares.u16()
            val key = shares.vector16(MAX_TLS_VECTOR_SIZE)
            if (group == GROUP_SECP256R1) p256 = key
        }
        val key = p256 ?: fail("ClientHello does not contain a P-256 key share")
        if (key.size != 65 || key[0] != 0x04.toByte()) fail("Invalid P-256 key share")
        return Tls13ClientHello(
            random,
            key,
            required(extensions, EXT_QUIC_TRANSPORT_PARAMETERS, "QUIC transport parameters")
        )
    }

    fun encodeServerHello(value: Tls13ServerHello): TlsHandshakeMessage {
        require(value.random.size == 32)
        require(value.keyShare.size == 65)
        val extensions = encodeExtensions(
            EXT_SUPPORTED_VERSIONS to bytes { u16(TLS_1_3) },
            EXT_KEY_SHARE to bytes {
                u16(GROUP_SECP256R1)
                vector16(value.keyShare)
            }
        )
        return TlsHandshakeMessage(
            TlsHandshakeType.SERVER_HELLO,
            bytes {
                u16(TLS_LEGACY_VERSION)
                raw(value.random)
                u8(0)
                u16(TLS_AES_128_GCM_SHA256)
                u8(0)
                vector16(extensions)
            }
        )
    }

    fun decodeServerHello(message: TlsHandshakeMessage): Tls13ServerHello {
        if (message.type != TlsHandshakeType.SERVER_HELLO) unexpected(message)
        val reader = TlsReader(message.body)
        reader.requireU16(TLS_LEGACY_VERSION, "ServerHello legacy_version")
        val random = reader.bytes(32)
        if (random.contentEquals(HELLO_RETRY_REQUEST_RANDOM)) {
            fail("HelloRetryRequest is not supported")
        }
        reader.vector8(MAX_LEGACY_SESSION_ID_SIZE)
        reader.requireU16(TLS_AES_128_GCM_SHA256, "ServerHello cipher suite")
        if (reader.u8() != 0) fail("ServerHello compression is invalid")
        val extensions = decodeExtensions(reader.vector16(MAX_TLS_VECTOR_SIZE))
        reader.requireEnd("ServerHello")
        val versions = TlsReader(required(extensions, EXT_SUPPORTED_VERSIONS, "supported_versions"))
        versions.requireU16(TLS_1_3, "selected TLS version")
        versions.requireEnd("supported_versions")
        val keyShare = TlsReader(required(extensions, EXT_KEY_SHARE, "key_share"))
        keyShare.requireU16(GROUP_SECP256R1, "selected key share group")
        val key = keyShare.vector16(MAX_TLS_VECTOR_SIZE)
        keyShare.requireEnd("server key_share")
        if (key.size != 65 || key[0] != 0x04.toByte()) fail("Invalid P-256 key share")
        return Tls13ServerHello(random, key)
    }

    fun encodeEncryptedExtensions(value: Tls13EncryptedExtensions): TlsHandshakeMessage =
        TlsHandshakeMessage(
            TlsHandshakeType.ENCRYPTED_EXTENSIONS,
            vector16(
                encodeExtensions(
                    EXT_ALPN to alpnExtension(),
                    EXT_QUIC_TRANSPORT_PARAMETERS to value.transportParameters
                )
            )
        )

    fun decodeEncryptedExtensions(message: TlsHandshakeMessage): Tls13EncryptedExtensions {
        if (message.type != TlsHandshakeType.ENCRYPTED_EXTENSIONS) unexpected(message)
        val reader = TlsReader(message.body)
        val extensions = decodeExtensions(reader.vector16(MAX_TLS_VECTOR_SIZE))
        reader.requireEnd("EncryptedExtensions")
        requireAlpn(extensions)
        return Tls13EncryptedExtensions(
            required(extensions, EXT_QUIC_TRANSPORT_PARAMETERS, "QUIC transport parameters")
        )
    }

    private fun requireClientProfile(extensions: Map<Int, ByteArray>) {
        val versions = TlsReader(required(extensions, EXT_SUPPORTED_VERSIONS, "supported_versions"))
        val offeredVersions = TlsReader(versions.vector8(254))
        versions.requireEnd("supported_versions")
        var tls13 = false
        while (offeredVersions.remaining > 0) {
            if (offeredVersions.u16() == TLS_1_3) tls13 = true
        }
        if (!tls13) fail("ClientHello does not offer TLS 1.3")

        val groups = TlsReader(required(extensions, EXT_SUPPORTED_GROUPS, "supported_groups"))
        val groupList = TlsReader(groups.vector16(MAX_TLS_VECTOR_SIZE))
        groups.requireEnd("supported_groups")
        var p256 = false
        while (groupList.remaining > 0) {
            if (groupList.u16() == GROUP_SECP256R1) p256 = true
        }
        if (!p256) fail("ClientHello does not offer secp256r1")

        val signatures = TlsReader(
            required(extensions, EXT_SIGNATURE_ALGORITHMS, "signature_algorithms")
        )
        val signatureList = TlsReader(signatures.vector16(MAX_TLS_VECTOR_SIZE))
        signatures.requireEnd("signature_algorithms")
        var ecdsa = false
        while (signatureList.remaining > 0) {
            if (signatureList.u16() == SIGNATURE_ECDSA_SECP256R1_SHA256) ecdsa = true
        }
        if (!ecdsa) fail("ClientHello does not offer ecdsa_secp256r1_sha256")
        requireAlpn(extensions)
    }

    private fun requireAlpn(extensions: Map<Int, ByteArray>) {
        val alpn = TlsReader(required(extensions, EXT_ALPN, "ALPN"))
        val protocols = TlsReader(alpn.vector16(MAX_TLS_VECTOR_SIZE))
        alpn.requireEnd("ALPN")
        var selected = false
        while (protocols.remaining > 0) {
            if (protocols.vector8(255).decodeToString() == KQUIC_ALPN) selected = true
        }
        if (!selected) fail("Peer did not negotiate ALPN $KQUIC_ALPN")
    }

    private fun encodeExtensions(vararg extensions: Pair<Int, ByteArray>): ByteArray = bytes {
        extensions.forEach { (type, value) ->
            u16(type)
            vector16(value)
        }
    }

    private fun decodeExtensions(encoded: ByteArray): Map<Int, ByteArray> {
        val reader = TlsReader(encoded)
        val extensions = linkedMapOf<Int, ByteArray>()
        while (reader.remaining > 0) {
            val type = reader.u16()
            val value = reader.vector16(MAX_TLS_VECTOR_SIZE)
            if (extensions.put(type, value) != null) fail("Duplicate TLS extension $type")
        }
        return extensions
    }

    private fun alpnExtension(): ByteArray =
        vector16(bytes { vector8(KQUIC_ALPN.encodeToByteArray()) })

    private fun required(extensions: Map<Int, ByteArray>, type: Int, name: String): ByteArray =
        extensions[type] ?: fail("Missing TLS extension $name")

    private fun unexpected(message: TlsHandshakeMessage): Nothing =
        fail("Unexpected TLS handshake message ${message.type}")

    private fun vector16(value: ByteArray): ByteArray = bytes { vector16(value) }
}

private class TlsWriter {
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
    fun raw(value: ByteArray) {
        output += value.toList()
    }
    fun vector8(value: ByteArray) {
        u8(value.size)
        raw(value)
    }
    fun vector16(value: ByteArray) {
        u16(value.size)
        raw(value)
    }
    fun build(): ByteArray = output.toByteArray()
}

private class TlsReader(private val input: ByteArray) {
    private var position = 0
    val remaining: Int get() = input.size - position

    fun u8(): Int {
        if (remaining < 1) fail("Truncated TLS message")
        return input[position++].toInt() and 0xff
    }
    fun u16(): Int = (u8() shl 8) or u8()
    fun bytes(size: Int): ByteArray {
        if (size < 0 || size > remaining) fail("Truncated TLS vector")
        return input.copyOfRange(position, position + size).also { position += size }
    }
    fun vector8(maximum: Int): ByteArray {
        val size = u8()
        if (size > maximum) fail("TLS vector exceeds configured limit")
        return bytes(size)
    }
    fun vector16(maximum: Int): ByteArray {
        val size = u16()
        if (size > maximum) fail("TLS vector exceeds configured limit")
        return bytes(size)
    }
    fun requireU16(expected: Int, field: String) {
        if (u16() != expected) fail("Unsupported $field")
    }
    fun requireEnd(field: String) {
        if (remaining != 0) fail("$field contains trailing bytes")
    }
}

private inline fun bytes(block: TlsWriter.() -> Unit): ByteArray =
    TlsWriter().apply(block).build()

private fun fail(message: String): Nothing = throw KQuicError.ProtocolViolation(message)

private const val TLS_LEGACY_VERSION = 0x0303
private const val TLS_1_3 = 0x0304
private const val TLS_AES_128_GCM_SHA256 = 0x1301
private const val GROUP_SECP256R1 = 0x0017
private const val SIGNATURE_ECDSA_SECP256R1_SHA256 = 0x0403
private const val EXT_SUPPORTED_GROUPS = 10
private const val EXT_SIGNATURE_ALGORITHMS = 13
private const val EXT_ALPN = 16
private const val EXT_SUPPORTED_VERSIONS = 43
private const val EXT_KEY_SHARE = 51
private const val EXT_QUIC_TRANSPORT_PARAMETERS = 57
private const val MAX_LEGACY_SESSION_ID_SIZE = 32
private const val MAX_TLS_VECTOR_SIZE = 65_535
private const val KQUIC_ALPN = "kquic/1"
private val HELLO_RETRY_REQUEST_RANDOM = byteArrayOf(
    0xcf.toByte(), 0x21, 0xad.toByte(), 0x74, 0xe5.toByte(), 0x9a.toByte(), 0x61, 0x11,
    0xbe.toByte(), 0x1d, 0x8c.toByte(), 0x02, 0x1e, 0x65, 0xb8.toByte(), 0x91.toByte(),
    0xc2.toByte(), 0xa2.toByte(), 0x11, 0x16, 0x7a, 0xbb.toByte(), 0x8c.toByte(), 0x5e,
    0x07, 0x9e.toByte(), 0x09, 0xe2.toByte(), 0xc8.toByte(), 0xa8.toByte(), 0x33, 0x9c.toByte()
)
