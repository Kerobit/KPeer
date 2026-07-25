package com.kerobit.kquic.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals

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

    private fun String.hex(): ByteArray {
        require(length % 2 == 0)
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }
}
