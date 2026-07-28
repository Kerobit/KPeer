package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError

internal data class QuicOpenedPacket(
    val packetNumber: ULong,
    val encryptionLevel: QuicEncryptionLevel,
    val destinationConnectionId: ByteArray,
    val sourceConnectionId: ByteArray?,
    val plaintext: ByteArray,
    val consumedBytes: Int
)

/**
 * Complete packet protection stage: header construction, AEAD, header protection and inverse
 * parsing. The connection actor supplies keys and owns packet-number spaces.
 */
internal class QuicPacketProtection(
    primitives: QuicCryptoPrimitives
) {
    private val crypto = QuicInitialCrypto(primitives)

    fun sealLong(
        type: QuicLongPacketType,
        destinationConnectionId: ByteArray,
        sourceConnectionId: ByteArray,
        token: ByteArray = ByteArray(0),
        packetNumber: ULong,
        packetNumberLength: Int,
        plaintext: ByteArray,
        keys: QuicPacketProtectionKeys,
        padInitialDatagram: Boolean = type == QuicLongPacketType.INITIAL
    ): ByteArray {
        if (type == QuicLongPacketType.ZERO_RTT) {
            throw KQuicError.ProtocolViolation("KQuic does not support 0-RTT")
        }
        var paddedPlaintext = plaintext.copyOf()
        val minimumForSample = 4 - packetNumberLength
        if (paddedPlaintext.size < minimumForSample) {
            paddedPlaintext = paddedPlaintext.copyOf(minimumForSample)
        }
        if (padInitialDatagram) {
            var attempts = 0
            while (attempts++ < 4) {
                val estimated = unprotectedLongPacket(
                    type,
                    destinationConnectionId,
                    sourceConnectionId,
                    token,
                    packetNumber,
                    packetNumberLength,
                    paddedPlaintext,
                    keys
                )
                val difference = QuicInitialDatagrams.MINIMUM_SIZE - estimated.size
                if (difference == 0) break
                val targetPlaintextSize = (paddedPlaintext.size + difference)
                    .coerceAtLeast(maxOf(plaintext.size, minimumForSample))
                paddedPlaintext = paddedPlaintext.copyOf(targetPlaintextSize)
            }
        }
        val unprotected = unprotectedLongPacket(
            type,
            destinationConnectionId,
            sourceConnectionId,
            token,
            packetNumber,
            packetNumberLength,
            paddedPlaintext,
            keys
        )
        val header = QuicLongHeaders.decode(unprotected)
        val mask = crypto.headerProtectionMask(keys, QuicHeaderProtection.sample(unprotected, header.packetNumberOffset))
        return applyHeaderProtection(unprotected, header.packetNumberOffset, true, mask)
    }

    fun sealShort(
        destinationConnectionId: ByteArray,
        packetNumber: ULong,
        packetNumberLength: Int,
        keyPhase: Boolean,
        plaintext: ByteArray,
        keys: QuicPacketProtectionKeys
    ): ByteArray {
        require(destinationConnectionId.size <= 20)
        require(packetNumberLength in 1..4)
        val minimumForSample = 4 - packetNumberLength
        val padded = if (plaintext.size < minimumForSample) {
            plaintext.copyOf(minimumForSample)
        } else {
            plaintext
        }
        val first = 0x40 or (if (keyPhase) 0x04 else 0) or (packetNumberLength - 1)
        val packetNumberBytes = encodePacketNumber(packetNumber, packetNumberLength)
        val header = byteArrayOf(first.toByte()) + destinationConnectionId + packetNumberBytes
        val ciphertext = crypto.seal(keys, packetNumber, header, padded)
        val unprotected = header + ciphertext
        val packetNumberOffset = 1 + destinationConnectionId.size
        val mask = crypto.headerProtectionMask(keys, QuicHeaderProtection.sample(unprotected, packetNumberOffset))
        return applyHeaderProtection(unprotected, packetNumberOffset, false, mask)
    }

    fun openLong(
        protectedPacket: ByteArray,
        largestReceived: ULong?,
        keys: QuicPacketProtectionKeys
    ): QuicOpenedPacket {
        val invariant = QuicInvariantHeaders.decode(protectedPacket) as? QuicInvariantHeader.Long
            ?: throw KQuicError.ProtocolViolation("Expected long-header packet")
        if (invariant.version != QUIC_VERSION_1) {
            throw KQuicError.ProtocolViolation("Unsupported QUIC version ${invariant.version}")
        }
        val protectedFields = protectedLongFields(protectedPacket, invariant)
        val mask = crypto.headerProtectionMask(
            keys,
            QuicHeaderProtection.sample(protectedPacket, protectedFields.packetNumberOffset)
        )
        val unprotected = QuicHeaderProtection.remove(
            protectedPacket,
            protectedFields.packetNumberOffset,
            longHeader = true,
            mask
        )
        val header = QuicLongHeaders.decode(unprotected)
        val truncated = decodePacketNumber(unprotected, header.packetNumberOffset, header.packetNumberLength)
        val packetNumber = QuicPacketNumbers.decode(largestReceived, truncated, header.packetNumberLength)
        val payloadEnd = header.packetNumberOffset + header.payloadLength.toInt()
        val payloadStart = header.packetNumberOffset + header.packetNumberLength
        val aad = unprotected.copyOfRange(0, payloadStart)
        val plaintext = crypto.open(keys, packetNumber, aad, unprotected.copyOfRange(payloadStart, payloadEnd))
        return QuicOpenedPacket(
            packetNumber = packetNumber,
            encryptionLevel = when (header.type) {
                QuicLongPacketType.INITIAL -> QuicEncryptionLevel.INITIAL
                QuicLongPacketType.HANDSHAKE -> QuicEncryptionLevel.HANDSHAKE
                QuicLongPacketType.ZERO_RTT ->
                    throw KQuicError.ProtocolViolation("KQuic does not support 0-RTT")
            },
            destinationConnectionId = header.destinationConnectionId,
            sourceConnectionId = header.sourceConnectionId,
            plaintext = plaintext,
            consumedBytes = payloadEnd
        )
    }

    fun openShort(
        protectedPacket: ByteArray,
        destinationConnectionIdLength: Int,
        largestReceived: ULong?,
        keys: QuicPacketProtectionKeys
    ): QuicOpenedPacket {
        val invariant = QuicInvariantHeaders.decode(
            protectedPacket,
            destinationConnectionIdLength
        ) as? QuicInvariantHeader.Short
            ?: throw KQuicError.ProtocolViolation("Expected short-header packet")
        val mask = crypto.headerProtectionMask(keys, QuicHeaderProtection.sample(protectedPacket, invariant.packetNumberOffset))
        val unprotected = QuicHeaderProtection.remove(protectedPacket, invariant.packetNumberOffset, longHeader = false, mask)
        val packetNumberLength = (unprotected[0].toInt() and 0x03) + 1
        val truncated = decodePacketNumber(unprotected, invariant.packetNumberOffset, packetNumberLength)
        val packetNumber = QuicPacketNumbers.decode(largestReceived, truncated, packetNumberLength)
        val payloadStart = invariant.packetNumberOffset + packetNumberLength
        return QuicOpenedPacket(
            packetNumber = packetNumber,
            encryptionLevel = QuicEncryptionLevel.ONE_RTT,
            destinationConnectionId = invariant.destinationConnectionId,
            sourceConnectionId = null,
            plaintext = crypto.open(
                keys,
                packetNumber,
                unprotected.copyOfRange(0, payloadStart),
                unprotected.copyOfRange(payloadStart, unprotected.size)
            ),
            consumedBytes = protectedPacket.size
        )
    }

    private fun unprotectedLongPacket(
        type: QuicLongPacketType,
        destinationConnectionId: ByteArray,
        sourceConnectionId: ByteArray,
        token: ByteArray,
        packetNumber: ULong,
        packetNumberLength: Int,
        plaintext: ByteArray,
        keys: QuicPacketProtectionKeys
    ): ByteArray {
        val payloadLength = packetNumberLength + plaintext.size + AEAD_TAG_LENGTH
        val header = QuicLongHeaders.encode(
            type,
            destinationConnectionId,
            sourceConnectionId,
            token,
            payloadLength.toULong(),
            packetNumberLength
        )
        val packetNumberBytes = encodePacketNumber(packetNumber, packetNumberLength)
        val aad = header + packetNumberBytes
        return aad + crypto.seal(keys, packetNumber, aad, plaintext)
    }

    private fun protectedLongFields(
        packet: ByteArray,
        invariant: QuicInvariantHeader.Long
    ): ProtectedLongFields {
        val type = (invariant.firstByte ushr 4) and 0x03
        if (type == 3) throw KQuicError.ProtocolViolation("Retry is not AEAD packet protected")
        val reader = ByteReader(packet.copyOfRange(invariant.fieldsOffset, packet.size))
        if (type == 0) {
            val tokenLength = reader.varInt().checkedLength("Initial token")
            reader.readBytes(tokenLength)
        }
        val payloadLength = reader.varInt().checkedLength("QUIC payload")
        val packetNumberOffset = packet.size - reader.remaining
        if (packetNumberOffset + payloadLength > packet.size) {
            throw KQuicError.ProtocolViolation("Truncated protected QUIC packet")
        }
        return ProtectedLongFields(packetNumberOffset)
    }

    private fun applyHeaderProtection(
        packet: ByteArray,
        packetNumberOffset: Int,
        longHeader: Boolean,
        mask: ByteArray
    ): ByteArray {
        val protected = packet.copyOf()
        val firstMask = if (longHeader) 0x0f else 0x1f
        protected[0] = (
            protected[0].toInt() xor (mask[0].toInt() and firstMask)
            ).toByte()
        val packetNumberLength = (packet[0].toInt() and 0x03) + 1
        repeat(packetNumberLength) { index ->
            protected[packetNumberOffset + index] = (
                protected[packetNumberOffset + index].toInt() xor mask[index + 1].toInt()
                ).toByte()
        }
        return protected
    }

    private fun encodePacketNumber(packetNumber: ULong, length: Int): ByteArray {
        val truncated = QuicPacketNumbers.truncate(packetNumber, length)
        return ByteArray(length) { index ->
            (truncated shr ((length - index - 1) * 8)).toByte()
        }
    }

    private fun decodePacketNumber(packet: ByteArray, offset: Int, length: Int): ULong {
        var result = 0uL
        repeat(length) { index ->
            result = (result shl 8) or (packet[offset + index].toInt() and 0xff).toULong()
        }
        return result
    }

    private fun ULong.checkedLength(field: String): Int {
        if (this > Int.MAX_VALUE.toULong()) {
            throw KQuicError.ProtocolViolation("$field is too large")
        }
        return toInt()
    }

    private data class ProtectedLongFields(val packetNumberOffset: Int)
}

private const val AEAD_TAG_LENGTH = 16
