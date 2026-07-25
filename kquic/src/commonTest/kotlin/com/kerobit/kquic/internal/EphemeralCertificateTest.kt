package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicIdentity
import com.kerobit.kquic.KQuicSigner
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class EphemeralCertificateTest {
    @Test
    fun common_certificate_builder_preserves_spki_and_signs_the_tbs() = runBlocking {
        val publicPoint = ByteArray(65).also {
            it[0] = 0x04
            for (index in 1 until it.size) it[index] = index.toByte()
        }
        val spki = p256SubjectPublicKeyInfo(publicPoint)
        var signedBytes = ByteArray(0)
        val signer = KQuicSigner { _, message ->
            signedBytes = message
            byteArrayOf(0x30, 0)
        }
        val identity = createEphemeralKQuicIdentity(
            subjectPublicKeyInfoDer = spki,
            randomSerial = ByteArray(16) { (it + 1).toByte() },
            sha256 = { ByteArray(32) { 0xab.toByte() } },
            signer = signer
        )

        assertEquals(64, identity.cachedPublicKeySha256?.length)
        assertContentEquals(spki, extractSubjectPublicKeyInfo(identity.certificateChainDer.first()))
        assertContentEquals(signedBytes, DerTestReader(identity.certificateChainDer.first()).tbs())

        // The public constructor intentionally requires no private-key handle or fingerprint.
        KQuicIdentity(identity.certificateChainDer, signer)
        Unit
    }
}

private class DerTestReader(private val certificate: ByteArray) {
    fun tbs(): ByteArray {
        var position = headerLength(0)
        val start = position
        val total = elementLength(position)
        return certificate.copyOfRange(start, start + total)
    }

    private fun elementLength(offset: Int): Int {
        val lengthByte = certificate[offset + 1].toInt() and 0xff
        return if ((lengthByte and 0x80) == 0) {
            2 + lengthByte
        } else {
            val count = lengthByte and 0x7f
            var length = 0
            repeat(count) { index ->
                length = (length shl 8) or (certificate[offset + 2 + index].toInt() and 0xff)
            }
            2 + count + length
        }
    }

    private fun headerLength(offset: Int): Int {
        val lengthByte = certificate[offset + 1].toInt() and 0xff
        return offset + if ((lengthByte and 0x80) == 0) 2 else 2 + (lengthByte and 0x7f)
    }
}
