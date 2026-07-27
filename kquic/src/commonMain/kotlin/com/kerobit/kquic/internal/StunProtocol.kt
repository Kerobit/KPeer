package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicConnectivityCredentials
import com.kerobit.kquic.KQuicError

internal data class StunMessage(
    val type: Int,
    val transactionId: ByteArray,
    val attributes: List<StunAttribute>,
    val integrityValid: Boolean?
) {
    fun attribute(type: Int): ByteArray? =
        attributes.firstOrNull { it.type == type }?.value
}

internal data class StunAttribute(val type: Int, val value: ByteArray)

internal object StunProtocol {
    const val BINDING_REQUEST = 0x0001
    const val BINDING_SUCCESS_RESPONSE = 0x0101
    const val USERNAME = 0x0006
    const val MESSAGE_INTEGRITY_SHA256 = 0x001c
    const val XOR_MAPPED_ADDRESS = 0x0020
    const val PRIORITY = 0x0024
    const val USE_CANDIDATE = 0x0025
    const val ICE_CONTROLLED = 0x8029
    const val ICE_CONTROLLING = 0x802a
    const val MAGIC_COOKIE = 0x2112a442
    const val HEADER_SIZE = 20

    fun bindingDiscoveryRequest(transactionId: ByteArray): ByteArray =
        encode(BINDING_REQUEST, transactionId, emptyList(), null)

    fun connectivityRequest(
        transactionId: ByteArray,
        local: KQuicConnectivityCredentials,
        remote: KQuicConnectivityCredentials,
        controlling: Boolean,
        useCandidate: Boolean,
        priority: Long,
        tieBreaker: ULong,
        hmacSha256: (ByteArray, ByteArray) -> ByteArray
    ): ByteArray {
        val attributes = mutableListOf(
            StunAttribute(
                USERNAME,
                "${remote.usernameFragment}:${local.usernameFragment}".encodeToByteArray()
            ),
            StunAttribute(PRIORITY, u32(priority))
        )
        attributes += StunAttribute(
            if (controlling) ICE_CONTROLLING else ICE_CONTROLLED,
            u64(tieBreaker)
        )
        if (useCandidate) attributes += StunAttribute(USE_CANDIDATE, ByteArray(0))
        return encode(
            BINDING_REQUEST,
            transactionId,
            attributes,
            remote.password.encodeToByteArray(),
            hmacSha256
        )
    }

    fun bindingSuccessResponse(
        transactionId: ByteArray,
        mappedAddress: KQuicSocketAddress,
        integrityPassword: String?,
        hmacSha256: (ByteArray, ByteArray) -> ByteArray
    ): ByteArray = encode(
        BINDING_SUCCESS_RESPONSE,
        transactionId,
        listOf(StunAttribute(XOR_MAPPED_ADDRESS, xorMappedAddress(mappedAddress, transactionId))),
        integrityPassword?.encodeToByteArray(),
        hmacSha256
    )

    fun decode(
        bytes: ByteArray,
        integrityPassword: String? = null,
        hmacSha256: ((ByteArray, ByteArray) -> ByteArray)? = null
    ): StunMessage {
        if (bytes.size < HEADER_SIZE || bytes[0].toInt() and 0xc0 != 0) {
            throw KQuicError.ProtocolViolation("Invalid STUN header")
        }
        val type = readU16(bytes, 0)
        val length = readU16(bytes, 2)
        if (readU32(bytes, 4) != MAGIC_COOKIE.toLong() || length + HEADER_SIZE > bytes.size) {
            throw KQuicError.ProtocolViolation("Invalid STUN length or magic cookie")
        }
        val transactionId = bytes.copyOfRange(8, 20)
        val attributes = mutableListOf<StunAttribute>()
        var integrityValid: Boolean? = null
        var position = HEADER_SIZE
        val end = HEADER_SIZE + length
        while (position < end) {
            if (end - position < 4) throw KQuicError.ProtocolViolation("Truncated STUN attribute")
            val attributeStart = position
            val attributeType = readU16(bytes, position)
            val attributeLength = readU16(bytes, position + 2)
            position += 4
            if (attributeLength > end - position) {
                throw KQuicError.ProtocolViolation("Truncated STUN attribute value")
            }
            val value = bytes.copyOfRange(position, position + attributeLength)
            attributes += StunAttribute(attributeType, value)
            position += (attributeLength + 3) and -4
            if (attributeType == MESSAGE_INTEGRITY_SHA256) {
                val password = integrityPassword
                val hmac = hmacSha256
                integrityValid = if (password == null || hmac == null || value.size != 32) {
                    false
                } else {
                    val covered = bytes.copyOfRange(0, attributeStart)
                    writeU16(
                        covered,
                        2,
                        attributeStart + 4 + value.size - HEADER_SIZE
                    )
                    constantTimeEquals(
                        hmac(password.encodeToByteArray(), covered),
                        value
                    )
                }
            }
        }
        return StunMessage(type, transactionId, attributes, integrityValid)
    }

    fun mappedAddress(message: StunMessage): KQuicSocketAddress? =
        message.attribute(XOR_MAPPED_ADDRESS)?.let {
            decodeXorMappedAddress(it, message.transactionId)
        }

    fun isStun(bytes: ByteArray): Boolean =
        bytes.size >= HEADER_SIZE &&
            bytes[0].toInt() and 0xc0 == 0 &&
            readU32(bytes, 4) == MAGIC_COOKIE.toLong()

    private fun encode(
        type: Int,
        transactionId: ByteArray,
        attributes: List<StunAttribute>,
        integrityKey: ByteArray?,
        hmacSha256: ((ByteArray, ByteArray) -> ByteArray)? = null
    ): ByteArray {
        require(transactionId.size == 12)
        val encodedAttributes = attributes.fold(ByteArray(0)) { result, attribute ->
            result + encodeAttribute(attribute)
        }
        val integritySize = if (integrityKey != null) 36 else 0
        val header = ByteArray(HEADER_SIZE)
        writeU16(header, 0, type)
        writeU16(header, 2, encodedAttributes.size + integritySize)
        writeU32(header, 4, MAGIC_COOKIE.toLong())
        transactionId.copyInto(header, 8)
        val covered = header + encodedAttributes
        if (integrityKey == null) return covered
        val hmac = requireNotNull(hmacSha256)(integrityKey, covered)
        return covered + encodeAttribute(StunAttribute(MESSAGE_INTEGRITY_SHA256, hmac))
    }

    private fun encodeAttribute(attribute: StunAttribute): ByteArray {
        val padding = (4 - attribute.value.size % 4) % 4
        val header = ByteArray(4)
        writeU16(header, 0, attribute.type)
        writeU16(header, 2, attribute.value.size)
        return header + attribute.value + ByteArray(padding)
    }

    private fun xorMappedAddress(
        address: KQuicSocketAddress,
        transactionId: ByteArray
    ): ByteArray {
        val raw = parseIpAddress(address.host)
        val family = if (raw.size == 4) 0x01 else 0x02
        val mask = u32(MAGIC_COOKIE.toLong()) + transactionId
        val result = ByteArray(4 + raw.size)
        result[1] = family.toByte()
        writeU16(result, 2, address.port xor (MAGIC_COOKIE ushr 16))
        raw.indices.forEach { index ->
            result[4 + index] = (raw[index].toInt() xor mask[index].toInt()).toByte()
        }
        return result
    }

    private fun decodeXorMappedAddress(
        value: ByteArray,
        transactionId: ByteArray
    ): KQuicSocketAddress {
        if (value.size < 8) throw KQuicError.ProtocolViolation("Invalid XOR-MAPPED-ADDRESS")
        val family = value[1].toInt() and 0xff
        val length = when (family) {
            0x01 -> 4
            0x02 -> 16
            else -> throw KQuicError.ProtocolViolation("Unknown STUN address family")
        }
        if (value.size != 4 + length) {
            throw KQuicError.ProtocolViolation("Invalid XOR-MAPPED-ADDRESS length")
        }
        val mask = u32(MAGIC_COOKIE.toLong()) + transactionId
        val raw = ByteArray(length) { index ->
            (value[4 + index].toInt() xor mask[index].toInt()).toByte()
        }
        return KQuicSocketAddress(
            host = formatIpAddress(raw),
            port = readU16(value, 2) xor (MAGIC_COOKIE ushr 16)
        )
    }

    private fun parseIpAddress(host: String): ByteArray {
        if ('.' in host && ':' !in host) {
            val parts = host.split('.')
            if (parts.size != 4) throw KQuicError.ProtocolViolation("Invalid IPv4 address")
            return ByteArray(4) { parts[it].toInt().also { value -> require(value in 0..255) }.toByte() }
        }
        val halves = host.substringBefore('%').split("::", limit = 2)
        val left = halves[0].takeIf(String::isNotEmpty)?.split(':').orEmpty()
        val right = halves.getOrNull(1)?.takeIf(String::isNotEmpty)?.split(':').orEmpty()
        val missing = 8 - left.size - right.size
        if (missing < 0 || (halves.size == 1 && missing != 0)) {
            throw KQuicError.ProtocolViolation("Invalid IPv6 address")
        }
        val words = left + List(missing) { "0" } + right
        if (words.size != 8) throw KQuicError.ProtocolViolation("Invalid IPv6 address")
        return ByteArray(16).also { bytes ->
            words.forEachIndexed { index, word ->
                val value = word.toInt(16)
                bytes[index * 2] = (value ushr 8).toByte()
                bytes[index * 2 + 1] = value.toByte()
            }
        }
    }

    private fun formatIpAddress(raw: ByteArray): String = when (raw.size) {
        4 -> raw.joinToString(".") { (it.toInt() and 0xff).toString() }
        16 -> (0 until 8).joinToString(":") { index ->
            (((raw[index * 2].toInt() and 0xff) shl 8) or
                (raw[index * 2 + 1].toInt() and 0xff)).toString(16)
        }
        else -> throw KQuicError.ProtocolViolation("Invalid IP address length")
    }

    private fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean {
        if (first.size != second.size) return false
        var difference = 0
        first.indices.forEach { index ->
            difference = difference or (first[index].toInt() xor second[index].toInt())
        }
        return difference == 0
    }

    private fun u32(value: Long): ByteArray = ByteArray(4).also {
        writeU32(it, 0, value)
    }

    private fun u64(value: ULong): ByteArray = ByteArray(8) { index ->
        (value shr ((7 - index) * 8)).toByte()
    }

    private fun readU16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readU32(bytes: ByteArray, offset: Int): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    private fun writeU16(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value ushr 8).toByte()
        bytes[offset + 1] = value.toByte()
    }

    private fun writeU32(bytes: ByteArray, offset: Int, value: Long) {
        bytes[offset] = (value ushr 24).toByte()
        bytes[offset + 1] = (value ushr 16).toByte()
        bytes[offset + 2] = (value ushr 8).toByte()
        bytes[offset + 3] = value.toByte()
    }
}
