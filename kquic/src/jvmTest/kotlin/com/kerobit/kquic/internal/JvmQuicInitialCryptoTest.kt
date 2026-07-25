package com.kerobit.kquic.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith

class JvmQuicInitialCryptoTest {
    private val crypto = QuicInitialCrypto(JvmQuicCryptoPrimitives)
    private val destinationConnectionId = "8394c8f03e515708".hex()

    @Test
    fun derives_rfc_9001_client_initial_packet_keys() {
        val keys = crypto.derive(destinationConnectionId, QuicInitialRole.CLIENT)

        assertContentEquals("1f369613dd76d5467730efcbe3b1a22d".hex(), keys.key)
        assertContentEquals("fa044b2f42a3fd3b46fb255c".hex(), keys.iv)
        assertContentEquals("9f50449e04a0e810283a1e9933adedd2".hex(), keys.headerProtectionKey)
    }

    @Test
    fun derives_rfc_9001_server_initial_packet_keys() {
        val keys = crypto.derive(destinationConnectionId, QuicInitialRole.SERVER)

        assertContentEquals("cf3a5331653c364c88f0f379b6067e37".hex(), keys.key)
        assertContentEquals("0ac1493ca1905853b0bba03e".hex(), keys.iv)
        assertContentEquals("c206b8d9b9f0f37644430b490eeaa314".hex(), keys.headerProtectionKey)
    }

    @Test
    fun header_protection_mask_matches_rfc_9001_client_initial_vector() {
        val keys = crypto.derive(destinationConnectionId, QuicInitialRole.CLIENT)

        assertContentEquals(
            "437b9aec36".hex(),
            crypto.headerProtectionMask(keys, "d1b1c98dd7689fb8ec11d242b123dc9b".hex())
        )
    }

    @Test
    fun aes_gcm_packet_protection_authenticates_header_and_payload() {
        val keys = crypto.derive(destinationConnectionId, QuicInitialRole.CLIENT)
        val header = "c300000001088394c8f03e5157080000449e00000002".hex()
        val plaintext = "060040f1010000".hex()

        val ciphertext = crypto.seal(keys, 2uL, header, plaintext)

        assertContentEquals(plaintext, crypto.open(keys, 2uL, header, ciphertext))
    }

    @Test
    fun retry_integrity_rejects_a_modified_packet() {
        val integrity = QuicRetryIntegrity(JvmQuicCryptoPrimitives)
        val originalDestinationId = byteArrayOf(1, 2, 3, 4)
        val retryWithoutTag = byteArrayOf(0xf0.toByte(), 0, 0, 0, 1, 0, 0, 9, 8, 7)
        val packet = retryWithoutTag + integrity.tag(originalDestinationId, retryWithoutTag)
        integrity.validate(originalDestinationId, packet)

        val modified = packet.copyOf().also { it[5] = (it[5].toInt() xor 1).toByte() }
        assertFailsWith<IllegalStateException> {
            integrity.validate(originalDestinationId, modified)
        }
    }

    @Test
    fun complete_initial_and_short_packet_pipeline_roundTrips() {
        val protection = QuicPacketProtection(JvmQuicCryptoPrimitives)
        val clientKeys = crypto.derive(destinationConnectionId, QuicInitialRole.CLIENT)
        val initialPlaintext = QuicFrames.encode(
            QuicFrame.Crypto(0uL, byteArrayOf(1, 2, 3, 4))
        )
        val initial = protection.sealLong(
            type = QuicLongPacketType.INITIAL,
            destinationConnectionId = destinationConnectionId,
            sourceConnectionId = byteArrayOf(7, 8, 9),
            packetNumber = 2uL,
            packetNumberLength = 2,
            plaintext = initialPlaintext,
            keys = clientKeys
        )
        kotlin.test.assertEquals(1_200, initial.size)
        val openedInitial = protection.openLong(initial, largestReceived = 1uL, keys = clientKeys)
        kotlin.test.assertEquals(2uL, openedInitial.packetNumber)
        val cryptoFrame = QuicFrames.decodeAll(openedInitial.plaintext)
            .filterIsInstance<QuicFrame.Crypto>()
            .single()
        assertContentEquals(byteArrayOf(1, 2, 3, 4), cryptoFrame.data)

        val oneRttKeys = QuicPacketProtectionKeys(
            key = ByteArray(16) { it.toByte() },
            iv = ByteArray(12) { (it + 16).toByte() },
            headerProtectionKey = ByteArray(16) { (it + 32).toByte() }
        )
        val shortPlaintext = QuicFrames.encode(QuicFrame.Datagram(byteArrayOf(9, 8, 7)))
        val short = protection.sealShort(
            destinationConnectionId = byteArrayOf(4, 5, 6),
            packetNumber = 42uL,
            packetNumberLength = 2,
            keyPhase = false,
            plaintext = shortPlaintext,
            keys = oneRttKeys
        )
        val openedShort = protection.openShort(
            short,
            destinationConnectionIdLength = 3,
            largestReceived = 41uL,
            keys = oneRttKeys
        )
        kotlin.test.assertEquals(42uL, openedShort.packetNumber)
        val datagram = QuicFrames.decodeAll(openedShort.plaintext)
            .filterIsInstance<QuicFrame.Datagram>()
            .single()
        assertContentEquals(byteArrayOf(9, 8, 7), datagram.data)
    }

    @Test
    fun packet_pipeline_rejects_ciphertext_corruption() {
        val protection = QuicPacketProtection(JvmQuicCryptoPrimitives)
        val keys = crypto.derive(destinationConnectionId, QuicInitialRole.CLIENT)
        val packet = protection.sealLong(
            type = QuicLongPacketType.HANDSHAKE,
            destinationConnectionId = destinationConnectionId,
            sourceConnectionId = byteArrayOf(7),
            packetNumber = 1uL,
            packetNumberLength = 2,
            plaintext = QuicFrames.encode(QuicFrame.Ping),
            keys = keys,
            padInitialDatagram = false
        )
        val corrupted = packet.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }
        assertFailsWith<IllegalStateException> {
            protection.openLong(corrupted, largestReceived = null, keys = keys)
        }
    }

    private fun String.hex(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
