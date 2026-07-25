package com.kerobit.kquic.internal

import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCHmac
import platform.CoreCrypto.kCCHmacAlgSHA256
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyKeyExchangeResult
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateWithData
import platform.Security.SecKeyVerifySignature
import platform.Security.kSecAttrKeyClass
import platform.Security.kSecAttrKeyClassPublic
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDHKeyExchangeStandard
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256

@OptIn(ExperimentalForeignApi::class)
internal object AppleTls13HandshakeCrypto : Tls13HandshakeCrypto {
    override val primitives: Tls13HashPrimitives = AppleTlsKeySchedulePrimitives

    override fun createP256KeyAgreement(): Tls13KeyAgreement {
        val privateKey = appleCreateP256Key()
        val publicKey = checkNotNull(SecKeyCopyPublicKey(privateKey))
        val publicData = checkNotNull(SecKeyCopyExternalRepresentation(publicKey, null))
        val publicPoint = publicData.toKQuicByteArray()
        CFRelease(publicData)
        CFRelease(publicKey)
        return object : Tls13KeyAgreement {
            override val publicKey: ByteArray = publicPoint

            override fun deriveSharedSecret(peerPublicKey: ByteArray): ByteArray {
                val peer = createP256PublicKey(peerPublicKey)
                try {
                    val result = checkNotNull(
                        SecKeyCopyKeyExchangeResult(
                            privateKey,
                            kSecKeyAlgorithmECDHKeyExchangeStandard,
                            peer,
                            null,
                            null
                        )
                    ) { "Security.framework ECDH failed" }
                    try {
                        return result.toKQuicByteArray()
                    } finally {
                        CFRelease(result)
                    }
                } finally {
                    CFRelease(peer)
                }
            }
        }
    }

    override fun secureRandom(size: Int): ByteArray = appleSecureRandomBytes(size)

    override fun verifyEcdsaP256Sha256(
        subjectPublicKeyInfoDer: ByteArray,
        message: ByteArray,
        signature: ByteArray
    ): Boolean {
        val publicKey = createP256PublicKey(
            extractP256PublicPoint(subjectPublicKeyInfoDer)
        )
        val signedData = message.toAppleCFData()
        val signatureData = signature.toAppleCFData()
        return try {
            SecKeyVerifySignature(
                publicKey,
                kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                signedData,
                signatureData,
                null
            )
        } finally {
            CFRelease(signatureData)
            CFRelease(signedData)
            CFRelease(publicKey)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private object AppleTlsKeySchedulePrimitives : Tls13HashPrimitives {
    override fun sha256(data: ByteArray): ByteArray = appleSha256(data)

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val output = ByteArray(32)
        key.usePinned { keyPinned ->
            if (data.isEmpty()) {
                output.usePinned { outputPinned ->
                    CCHmac(
                        kCCHmacAlgSHA256,
                        keyPinned.addressOf(0),
                        key.size.convert(),
                        null,
                        0u,
                        outputPinned.addressOf(0)
                    )
                }
            } else {
                data.usePinned { dataPinned ->
                    output.usePinned { outputPinned ->
                        CCHmac(
                            kCCHmacAlgSHA256,
                            keyPinned.addressOf(0),
                            key.size.convert(),
                            dataPinned.addressOf(0),
                            data.size.convert(),
                            outputPinned.addressOf(0)
                        )
                    }
                }
            }
        }
        return output
    }

}

@OptIn(ExperimentalForeignApi::class)
private fun createP256PublicKey(publicPoint: ByteArray) = memScoped {
    require(publicPoint.size == 65 && publicPoint[0] == 0x04.toByte())
    val bitCount = alloc<IntVar>()
    bitCount.value = 256
    val bitCountNumber = checkNotNull(
        CFNumberCreate(null, kCFNumberIntType, bitCount.ptr)
    )
    val keys = allocArray<COpaquePointerVar>(3)
    val values = allocArray<COpaquePointerVar>(3)
    keys[0] = kSecAttrKeyType
    values[0] = kSecAttrKeyTypeECSECPrimeRandom
    keys[1] = kSecAttrKeyClass
    values[1] = kSecAttrKeyClassPublic
    keys[2] = kSecAttrKeySizeInBits
    values[2] = bitCountNumber
    val attributes = checkNotNull(
        CFDictionaryCreate(
            null,
            keys,
            values,
            3,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr
        )
    )
    val data = publicPoint.toAppleCFData()
    try {
        checkNotNull(SecKeyCreateWithData(data, attributes, null)) {
            "Security.framework rejected a P-256 public key"
        }
    } finally {
        CFRelease(data)
        CFRelease(attributes)
        CFRelease(bitCountNumber)
    }
}
