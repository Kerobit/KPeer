package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicIdentity
import com.kerobit.kquic.KQuicSigner

/**
 * Builds the deliberately small self-signed certificate used by
 * `ephemeralWithAuthenticatedSignaling`.
 *
 * Key generation, SHA-256 and ECDSA remain platform operations. Keeping the X.509 envelope here
 * gives Android and Apple exactly the same identity semantics and certificate profile.
 */
internal suspend fun createEphemeralKQuicIdentity(
    subjectPublicKeyInfoDer: ByteArray,
    randomSerial: ByteArray,
    sha256: (ByteArray) -> ByteArray,
    signer: KQuicSigner,
    releasePlatformKey: (() -> Unit)? = null
): KQuicIdentity {
    require(subjectPublicKeyInfoDer.isNotEmpty())
    require(randomSerial.isNotEmpty())

    val signatureAlgorithm = Der.sequence(Der.oid(OID_ECDSA_WITH_SHA256))
    val commonName = Der.sequence(Der.set(Der.sequence(Der.oid(OID_COMMON_NAME), Der.utf8("KQuic Ephemeral"))
        )
    )
    val serial = randomSerial.copyOf().also {
        it[0] = (it[0].toInt() and 0x7f).toByte()
        if (it.all { value -> value == 0.toByte() }) it[it.lastIndex] = 1
    }
    val extensions = Der.context(
        tagNumber = 3,
        content = Der.sequence(Der.sequence(Der.oid(OID_BASIC_CONSTRAINTS), Der.boolean(true), Der.octetString(Der.sequence())),
            Der.sequence(
                Der.oid(OID_KEY_USAGE),
                Der.boolean(true),
                Der.octetString(Der.bitString(byteArrayOf(0x80.toByte()), unusedBits = 7))
            )
        )
    )
    val toBeSigned = Der.sequence(
        Der.context(0, Der.integer(byteArrayOf(2))),
        Der.integer(serial),
        signatureAlgorithm,
        commonName,
        Der.sequence(Der.generalizedTime("20200101000000Z"), Der.generalizedTime("20491231235959Z")),
        commonName,
        subjectPublicKeyInfoDer,
        extensions
    )
    val signature = signer.sign(com.kerobit.kquic.KQuicSignatureAlgorithm.ECDSA_SECP256R1_SHA256, toBeSigned)
    val certificate = Der.sequence(toBeSigned, signatureAlgorithm, Der.bitString(signature))
    return KQuicIdentity.platformGenerated(
        certificateChainDer = listOf(certificate),
        signer = signer,
        publicKeySha256 = sha256(subjectPublicKeyInfoDer).toHex(),
        releasePlatformKey = releasePlatformKey
    )
}

/** Wraps an ANSI X9.63 uncompressed P-256 public point in SubjectPublicKeyInfo. */
internal fun p256SubjectPublicKeyInfo(publicPoint: ByteArray): ByteArray {
    require(publicPoint.size == 65 && publicPoint[0] == 0x04.toByte()) {
        "P-256 public key must be a 65-byte uncompressed X9.63 point"
    }
    return Der.sequence(Der.sequence(Der.oid(OID_EC_PUBLIC_KEY), Der.oid(OID_PRIME256V1)),
        Der.bitString(publicPoint)
    )
}

private object Der {
    fun sequence(vararg values: ByteArray): ByteArray = tagged(0x30, concatenate(values))
    fun set(vararg values: ByteArray): ByteArray = tagged(0x31, concatenate(values))
    fun context(tagNumber: Int, content: ByteArray): ByteArray {
        require(tagNumber in 0..30)
        return tagged(0xa0 or tagNumber, content)
    }

    fun integer(unsigned: ByteArray): ByteArray {
        val withoutLeadingZeros = unsigned.dropWhile { it == 0.toByte() }.toByteArray()
        val stripped = if (withoutLeadingZeros.isEmpty()) byteArrayOf(0) else withoutLeadingZeros
        val content = if ((stripped[0].toInt() and 0x80) != 0) {
            byteArrayOf(0) + stripped
        } else {
            stripped
        }
        return tagged(0x02, content)
    }

    fun boolean(value: Boolean): ByteArray =
        tagged(0x01, byteArrayOf(if (value) 0xff.toByte() else 0))

    fun bitString(value: ByteArray, unusedBits: Int = 0): ByteArray {
        require(unusedBits in 0..7)
        return tagged(0x03, byteArrayOf(unusedBits.toByte()) + value)
    }

    fun octetString(value: ByteArray): ByteArray = tagged(0x04, value)
    fun utf8(value: String): ByteArray = tagged(0x0c, value.encodeToByteArray())
    fun generalizedTime(value: String): ByteArray = tagged(0x18, value.encodeToByteArray())

    fun oid(value: String): ByteArray {
        val parts = value.split('.').map(String::toULong)
        require(parts.size >= 2 && parts[0] <= 2uL && parts[1] <= 39uL)
        val encoded = mutableListOf<Byte>()
        encoded += (parts[0] * 40uL + parts[1]).toByte()
        parts.drop(2).forEach { component ->
            val bytes = mutableListOf((component and 0x7fuL).toByte())
            var remaining = component shr 7
            while (remaining > 0uL) {
                bytes += ((remaining and 0x7fuL).toInt() or 0x80).toByte()
                remaining = remaining shr 7
            }
            encoded += bytes.asReversed()
        }
        return tagged(0x06, encoded.toByteArray())
    }

    private fun tagged(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + length(content.size) + content

    private fun length(value: Int): ByteArray {
        require(value >= 0)
        if (value < 128) return byteArrayOf(value.toByte())
        var remaining = value
        val bytes = mutableListOf<Byte>()
        while (remaining > 0) {
            bytes += (remaining and 0xff).toByte()
            remaining = remaining ushr 8
        }
        return byteArrayOf((0x80 or bytes.size).toByte()) + bytes.asReversed()
    }

    private fun concatenate(values: Array<out ByteArray>): ByteArray {
        val size = values.sumOf(ByteArray::size)
        val result = ByteArray(size)
        var offset = 0
        values.forEach { value ->
            value.copyInto(result, offset)
            offset += value.size
        }
        return result
    }
}

internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte ->
    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
}

private const val OID_COMMON_NAME = "2.5.4.3"
private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
private const val OID_KEY_USAGE = "2.5.29.15"
private const val OID_EC_PUBLIC_KEY = "1.2.840.10045.2.1"
private const val OID_PRIME256V1 = "1.2.840.10045.3.1.7"
private const val OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"
