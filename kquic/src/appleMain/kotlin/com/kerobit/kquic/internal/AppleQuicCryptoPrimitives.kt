package com.kerobit.kquic.internal

import com.kerobit.kquic.cryptokit.kquic_aes_gcm_open
import com.kerobit.kquic.cryptokit.kquic_aes_gcm_seal
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess

/** Apple QUIC crypto backed by CryptoKit GCM and CommonCrypto header protection. */
@OptIn(ExperimentalForeignApi::class)
internal object AppleQuicCryptoPrimitives : QuicCryptoPrimitives {
    override fun sha256(data: ByteArray): ByteArray = appleSha256(data)

    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        appleHmacSha256(key, data)

    override fun aesEcbEncrypt(key: ByteArray, block: ByteArray): ByteArray {
        require(key.size == AES_128_KEY_SIZE)
        require(block.size == AES_BLOCK_SIZE)
        val output = ByteArray(AES_BLOCK_SIZE)
        memScoped {
            val moved = alloc<ULongVar>()
            key.usePinned { keyPinned ->
                block.usePinned { blockPinned ->
                    output.usePinned { outputPinned ->
                        val status = CCCrypt(
                            kCCEncrypt,
                            kCCAlgorithmAES,
                            kCCOptionECBMode,
                            keyPinned.addressOf(0),
                            key.size.convert(),
                            null,
                            blockPinned.addressOf(0),
                            block.size.convert(),
                            outputPinned.addressOf(0),
                            output.size.convert(),
                            moved.ptr
                        )
                        check(status == kCCSuccess && moved.value.toInt() == AES_BLOCK_SIZE) {
                            "CommonCrypto AES-ECB failed with status $status"
                        }
                    }
                }
            }
        }
        return output
    }

    override fun aesGcmSeal(key: ByteArray, nonce: ByteArray, aad: ByteArray, plaintext: ByteArray) =
        crypt(key, nonce, aad, plaintext, plaintext.size + GCM_TAG_SIZE, false)

    override fun aesGcmOpen(key: ByteArray, nonce: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        require(ciphertext.size >= GCM_TAG_SIZE) { "AES-GCM ciphertext is missing its tag" }
        return crypt(key, nonce, aad, ciphertext, ciphertext.size - GCM_TAG_SIZE, true)
    }

    private fun crypt(
        key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray,
        outputSize: Int, opening: Boolean
    ): ByteArray {
        require(key.size == AES_128_KEY_SIZE)
        require(nonce.size == QUIC_NONCE_SIZE)
        val output = ByteArray(outputSize)
        val result = key.usePinned { keyPinned ->
            nonce.usePinned { noncePinned ->
                aad.usePinned { aadPinned ->
                    input.usePinned { inputPinned ->
                        output.usePinned { outputPinned ->
                            val operation = if (opening) ::kquic_aes_gcm_open else ::kquic_aes_gcm_seal
                            operation(
                                keyPinned.addressOf(0).reinterpret(), key.size,
                                noncePinned.addressOf(0).reinterpret(), nonce.size,
                                aadPinned.addressOrNull(aad.size), aad.size,
                                inputPinned.addressOrNull(input.size), input.size,
                                outputPinned.addressOrNull(output.size), output.size
                            )
                        }
                    }
                }
            }
        }
        if (result < 0) {
            output.fill(0)
            val message = if (result == AUTHENTICATION_FAILURE) "AES-GCM authentication failed"
                else "CryptoKit AES-GCM failed with status $result"
            throw IllegalArgumentException(message)
        }
        check(result == output.size) { "CryptoKit AES-GCM returned an invalid output length" }
        return output
    }

    private fun kotlinx.cinterop.Pinned<ByteArray>.addressOrNull(size: Int) =
        if (size == 0) null else addressOf(0).reinterpret<kotlinx.cinterop.UByteVar>()
}

private const val AES_128_KEY_SIZE = 16
private const val AES_BLOCK_SIZE = 16
private const val QUIC_NONCE_SIZE = 12
private const val GCM_TAG_SIZE = 16
private const val AUTHENTICATION_FAILURE = -4
