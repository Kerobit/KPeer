package com.kerobit.kquic.internal

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFails

class AppleQuicCryptoPrimitivesTest {
    @Test
    fun initial_aes_and_header_protection_vectors_match_rfc_and_nist() {
        val primitives = AppleQuicCryptoPrimitives
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

    @Test
    fun cryptokit_gcm_handles_empty_large_and_authenticated_payloads() {
        val primitives = AppleQuicCryptoPrimitives
        assertContentEquals(
            "58e2fccefa7e3061367f1d57a4e7455a".hex(),
            primitives.aesGcmSeal(ByteArray(16), ByteArray(12), ByteArray(0), ByteArray(0))
        )

        val key = "feffe9928665731c6d6a8f9467308308".hex()
        val nonce = "cafebabefacedbaddecaf888".hex()
        val aad = "feedfacedeadbeeffeedfacedeadbeefabaddad2".hex()
        val plaintext = (
            "d9313225f88406e5a55909c5aff5269a" +
                "86a7a9531534f7da2e4c303d8a318a72" +
                "1c3c0c95956809532fcf0e2449a6b525" +
                "b16aedf5aa0de657ba637b39"
            ).hex()
        val expected = (
            "42831ec2217774244b7221b784d0d49c" +
                "e3aa212f2c02a4e035c17e2329aca12e" +
                "21d514b25466931c7d8f6a5aac84aa05" +
                "1ba30b396a0aac973d58e091" +
                "5bc94fbc3221a5db94fae95ae7121a47"
            ).hex()
        val sealed = primitives.aesGcmSeal(key, nonce, aad, plaintext)
        assertContentEquals(expected, sealed)
        assertContentEquals(plaintext, primitives.aesGcmOpen(key, nonce, aad, sealed))

        val large = ByteArray(1_150) { (it * 29).toByte() }
        val largeSealed = primitives.aesGcmSeal(key, nonce, aad, large)
        assertContentEquals(large, primitives.aesGcmOpen(key, nonce, aad, largeSealed))
        assertFails {
            primitives.aesGcmOpen(
                key, nonce, aad.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, sealed
            )
        }
        assertFails {
            primitives.aesGcmOpen(
                key, nonce, aad, sealed.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
            )
        }
        assertFails { primitives.aesGcmSeal(ByteArray(15), nonce, aad, plaintext) }
        assertFails { primitives.aesGcmSeal(key, ByteArray(11), aad, plaintext) }
    }

    private fun String.hex(): ByteArray = ByteArray(length / 2) { index ->
        substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
}
