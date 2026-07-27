package com.kerobit.kquic.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

class AndroidQuicCryptoPrimitivesTest {
    @Test
    fun initial_aes_and_header_protection_vectors_match_rfc_and_nist() {
        val primitives = AndroidQuicCryptoPrimitives
        val initial = QuicInitialCrypto(primitives)
        val keys = initial.derive("8394c8f03e515708".hex(), QuicInitialRole.CLIENT)
        assertContentEquals("1f369613dd76d5467730efcbe3b1a22d".hex(), keys.key)
        assertContentEquals("fa044b2f42a3fd3b46fb255c".hex(), keys.iv)
        assertContentEquals(
            "437b9aec36".hex(),
            initial.headerProtectionMask(
                keys,
                "d1b1c98dd7689fb8ec11d242b123dc9b".hex()
            )
        )

        val sealed = primitives.aesGcmSeal(
            key = ByteArray(16),
            nonce = ByteArray(12),
            aad = ByteArray(0),
            plaintext = ByteArray(16)
        )
        assertContentEquals(
            "0388dace60b6a392f328c2b971b2fe78ab6e47d42cec13bdf53a67b21257bddf".hex(),
            sealed
        )
        assertContentEquals(
            ByteArray(16),
            primitives.aesGcmOpen(ByteArray(16), ByteArray(12), ByteArray(0), sealed)
        )
        assertFails {
            primitives.aesGcmOpen(
                ByteArray(16),
                ByteArray(12),
                ByteArray(0),
                sealed.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            )
        }
    }

    private fun String.hex(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
