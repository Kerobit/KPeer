package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicError

/** Extracts the complete DER SubjectPublicKeyInfo element from an X.509 certificate. */
internal fun extractSubjectPublicKeyInfo(certificateDer: ByteArray): ByteArray {
    val certificate = DerCursor(certificateDer).element(expectedTag = 0x30)
    val certificateFields = DerCursor(certificate.content)
    val toBeSigned = certificateFields.element(expectedTag = 0x30)
    val fields = DerCursor(toBeSigned.content)
    if (fields.peekTag() == 0xa0) fields.element()
    fields.element(expectedTag = 0x02)
    fields.element(expectedTag = 0x30)
    fields.element(expectedTag = 0x30)
    fields.element(expectedTag = 0x30)
    fields.element(expectedTag = 0x30)
    return fields.element(expectedTag = 0x30).encoded
}

internal fun extractP256PublicPoint(subjectPublicKeyInfoDer: ByteArray): ByteArray {
    val spki = DerCursor(subjectPublicKeyInfoDer).element(expectedTag = 0x30)
    val fields = DerCursor(spki.content)
    fields.element(expectedTag = 0x30)
    val bitString = fields.element(expectedTag = 0x03).content
    if (bitString.size != 66 || bitString[0] != 0.toByte() ||
        bitString[1] != 0x04.toByte()
    ) {
        throw KQuicError.AuthenticationFailed("Certificate does not contain a P-256 public key")
    }
    return bitString.copyOfRange(1, bitString.size)
}

private data class DerElement(val encoded: ByteArray, val content: ByteArray)

private class DerCursor(private val source: ByteArray) {
    private var position = 0

    fun peekTag(): Int {
        if (position >= source.size) malformed()
        return source[position].toInt() and 0xff
    }

    fun element(expectedTag: Int? = null): DerElement {
        val start = position
        val tag = read()
        if (expectedTag != null && tag != expectedTag) malformed()
        val firstLength = read()
        val length = if ((firstLength and 0x80) == 0) {
            firstLength
        } else {
            val octets = firstLength and 0x7f
            if (octets == 0 || octets > 4) malformed()
            var value = 0
            repeat(octets) {
                if (value > (Int.MAX_VALUE ushr 8)) malformed()
                value = (value shl 8) or read()
            }
            value
        }
        if (length < 0 || length > source.size - position) malformed()
        val contentStart = position
        position += length
        return DerElement(encoded = source.copyOfRange(start, position), content = source.copyOfRange(contentStart, position))
    }

    private fun read(): Int {
        if (position >= source.size) malformed()
        return source[position++].toInt() and 0xff
    }

    private fun malformed(): Nothing =
        throw KQuicError.AuthenticationFailed("Malformed X.509 certificate")
}
