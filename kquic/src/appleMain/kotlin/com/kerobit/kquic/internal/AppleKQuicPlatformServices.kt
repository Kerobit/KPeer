package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicIdentityProvider
import com.kerobit.kquic.KQuicSignatureAlgorithm
import com.kerobit.kquic.KQuicSigner
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
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFNumberCreate
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFNumberIntType
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.SecRandomCopyBytes
import platform.Security.errSecSuccess
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecRandomDefault
import platform.posix.CLOCK_MONOTONIC
import platform.posix.clock_gettime
import platform.posix.timespec

internal actual fun createKQuicPlatformServices(platformContext: Any?): KQuicPlatformServices =
    AppleKQuicPlatformServices

@OptIn(ExperimentalForeignApi::class)
private object AppleKQuicPlatformServices : KQuicPlatformServices {
    override val ephemeralIdentityProvider = KQuicIdentityProvider {
        val privateKey = appleCreateP256Key()
        val publicKey = checkNotNull(SecKeyCopyPublicKey(privateKey)) {
            "Security.framework did not return the generated public key"
        }
        val publicPointData = checkNotNull(SecKeyCopyExternalRepresentation(publicKey, null)) {
            "Security.framework could not export the generated public key"
        }
        val publicPoint = publicPointData.toKQuicByteArray()
        CFRelease(publicPointData)
        CFRelease(publicKey)

        val signer = KQuicSigner { algorithm, message ->
            require(algorithm == KQuicSignatureAlgorithm.ECDSA_SECP256R1_SHA256)
            val input = message.toAppleCFData()
            try {
                val signature = checkNotNull(
                    SecKeyCreateSignature(
                        privateKey,
                        kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                        input,
                        null
                    )
                ) {
                    "Security.framework failed to create an ECDSA signature"
                }
                try {
                    signature.toKQuicByteArray()
                } finally {
                    CFRelease(signature)
                }
            } finally {
                CFRelease(input)
            }
        }
        createEphemeralKQuicIdentity(
            subjectPublicKeyInfoDer = p256SubjectPublicKeyInfo(publicPoint),
            randomSerial = appleSecureRandomBytes(16),
            sha256 = ::appleSha256,
            signer = signer,
            releasePlatformKey = { CFRelease(privateKey) }
        )
    }

    override val datagramSocketFactory: KQuicDatagramSocketFactory =
        AppleKQuicDatagramSocketFactory
    override val cryptoPrimitives: QuicCryptoPrimitives = AppleQuicCryptoPrimitives
    override val handshakeCrypto: Tls13HandshakeCrypto = AppleTls13HandshakeCrypto
    override val monotonicClock = KQuicMonotonicClock {
        memScoped {
            val time = alloc<timespec>()
            check(clock_gettime(CLOCK_MONOTONIC.toUInt(), time.ptr) == 0) {
                "clock_gettime(CLOCK_MONOTONIC) failed"
            }
            time.tv_sec * 1_000_000_000L + time.tv_nsec
        }
    }

    override fun secureRandom(size: Int): ByteArray = appleSecureRandomBytes(size)
    override fun hostAddresses(): List<String> = appleHostAddresses()

    override fun sha256(data: ByteArray): ByteArray = appleSha256(data)
}

@OptIn(ExperimentalForeignApi::class)
internal fun appleCreateP256Key() = memScoped {
    val bitCount = alloc<IntVar>()
    bitCount.value = 256
    val bitCountNumber = checkNotNull(
        CFNumberCreate(null, kCFNumberIntType, bitCount.ptr)
    )
    val keys = allocArray<COpaquePointerVar>(2)
    val values = allocArray<COpaquePointerVar>(2)
    keys[0] = kSecAttrKeyType
    values[0] = kSecAttrKeyTypeECSECPrimeRandom
    keys[1] = kSecAttrKeySizeInBits
    values[1] = bitCountNumber
    val parameters = checkNotNull(
        CFDictionaryCreate(
            null,
            keys,
            values,
            2,
            kCFTypeDictionaryKeyCallBacks.ptr,
            kCFTypeDictionaryValueCallBacks.ptr
        )
    )
    try {
        checkNotNull(SecKeyCreateRandomKey(parameters, null)) {
            "Security.framework failed to generate a P-256 key"
        }
    } finally {
        CFRelease(parameters)
        CFRelease(bitCountNumber)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun appleSecureRandomBytes(size: Int): ByteArray = ByteArray(size).also { output ->
    val status = output.usePinned { pinned ->
        SecRandomCopyBytes(kSecRandomDefault, size.convert(), pinned.addressOf(0))
    }
    check(status == errSecSuccess) { "SecRandomCopyBytes failed with OSStatus $status" }
}

@OptIn(ExperimentalForeignApi::class)
internal fun appleSha256(input: ByteArray): ByteArray {
    val output = ByteArray(CC_SHA256_DIGEST_LENGTH)
    if (input.isEmpty()) {
        output.usePinned { outputPinned ->
            checkNotNull(
                CC_SHA256(
                    null,
                    0u,
                    outputPinned.addressOf(0).reinterpret()
                )
            )
        }
    } else {
        input.usePinned { inputPinned ->
            output.usePinned { outputPinned ->
                checkNotNull(
                    CC_SHA256(
                        inputPinned.addressOf(0),
                        input.size.convert(),
                        outputPinned.addressOf(0).reinterpret()
                    )
                )
            }
        }
    }
    return output
}

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toAppleCFData() = usePinned { pinned ->
    checkNotNull(CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.toLong()))
}

@OptIn(ExperimentalForeignApi::class)
internal fun kotlinx.cinterop.CPointer<cnames.structs.__CFData>.toKQuicByteArray(): ByteArray {
    val length = CFDataGetLength(this).toInt()
    val bytes = checkNotNull(CFDataGetBytePtr(this))
    return ByteArray(length) { index -> bytes[index].toByte() }
}
