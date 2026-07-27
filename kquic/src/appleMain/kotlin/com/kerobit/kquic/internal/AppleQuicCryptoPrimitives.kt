package com.kerobit.kquic.internal

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreCrypto.CCCrypt
import platform.CoreCrypto.kCCAlgorithmAES
import platform.CoreCrypto.kCCEncrypt
import platform.CoreCrypto.kCCOptionECBMode
import platform.CoreCrypto.kCCSuccess

/**
 * Apple QUIC crypto backed by CommonCrypto's constant-time AES implementation.
 *
 * CommonCrypto's public API exposes AES-ECB but not its GCM SPI. GCM authentication and counter
 * composition therefore live here while every AES block operation remains in CommonCrypto.
 */
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

    override fun aesGcmSeal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray
    ): ByteArray {
        val hashSubkey = aesEcbEncrypt(key, ByteArray(AES_BLOCK_SIZE))
        val initialCounter = initialCounter(hashSubkey, nonce)
        val ciphertext = counterCrypt(key, initialCounter, plaintext)
        val authentication = ghash(hashSubkey, aad, ciphertext)
        val tagMask = aesEcbEncrypt(key, initialCounter)
        val tag = ByteArray(GCM_TAG_SIZE) { xorByte(tagMask[it], authentication[it]) }
        hashSubkey.fill(0)
        tagMask.fill(0)
        return ciphertext + tag
    }

    override fun aesGcmOpen(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray
    ): ByteArray {
        require(ciphertext.size >= GCM_TAG_SIZE) { "AES-GCM ciphertext is missing its tag" }
        val payload = ciphertext.copyOfRange(0, ciphertext.size - GCM_TAG_SIZE)
        val receivedTag = ciphertext.copyOfRange(ciphertext.size - GCM_TAG_SIZE, ciphertext.size)
        val hashSubkey = aesEcbEncrypt(key, ByteArray(AES_BLOCK_SIZE))
        val initialCounter = initialCounter(hashSubkey, nonce)
        val authentication = ghash(hashSubkey, aad, payload)
        val tagMask = aesEcbEncrypt(key, initialCounter)
        val expectedTag = ByteArray(GCM_TAG_SIZE) { xorByte(tagMask[it], authentication[it]) }
        hashSubkey.fill(0)
        tagMask.fill(0)
        if (!constantTimeEquals(receivedTag, expectedTag)) {
            expectedTag.fill(0)
            throw IllegalArgumentException("AES-GCM authentication failed")
        }
        expectedTag.fill(0)
        return counterCrypt(key, initialCounter, payload)
    }

    private fun initialCounter(hashSubkey: ByteArray, nonce: ByteArray): ByteArray =
        if (nonce.size == QUIC_NONCE_SIZE) {
            nonce + byteArrayOf(0, 0, 0, 1)
        } else {
            ghash(hashSubkey, ByteArray(0), nonce)
        }

    private fun counterCrypt(
        key: ByteArray,
        initialCounter: ByteArray,
        input: ByteArray
    ): ByteArray {
        val output = ByteArray(input.size)
        val counter = initialCounter.copyOf()
        var offset = 0
        while (offset < input.size) {
            incrementCounter(counter)
            val keyStream = aesEcbEncrypt(key, counter)
            val count = minOf(AES_BLOCK_SIZE, input.size - offset)
            repeat(count) { index ->
                output[offset + index] = xorByte(input[offset + index], keyStream[index])
            }
            keyStream.fill(0)
            offset += count
        }
        counter.fill(0)
        return output
    }

    private fun ghash(hashSubkey: ByteArray, aad: ByteArray, ciphertext: ByteArray): ByteArray {
        var accumulator = ByteArray(AES_BLOCK_SIZE)
        fun absorb(input: ByteArray) {
            var offset = 0
            while (offset < input.size) {
                val block = ByteArray(AES_BLOCK_SIZE)
                input.copyInto(block, 0, offset, minOf(offset + AES_BLOCK_SIZE, input.size))
                repeat(AES_BLOCK_SIZE) {
                    accumulator[it] = xorByte(accumulator[it], block[it])
                }
                accumulator = multiply(accumulator, hashSubkey)
                block.fill(0)
                offset += AES_BLOCK_SIZE
            }
        }
        absorb(aad)
        absorb(ciphertext)
        val lengths = ByteArray(AES_BLOCK_SIZE)
        writeBitLength(lengths, 0, aad.size)
        writeBitLength(lengths, 8, ciphertext.size)
        repeat(AES_BLOCK_SIZE) { accumulator[it] = xorByte(accumulator[it], lengths[it]) }
        return multiply(accumulator, hashSubkey)
    }

    private fun multiply(first: ByteArray, second: ByteArray): ByteArray {
        val product = ByteArray(AES_BLOCK_SIZE)
        val factor = second.copyOf()
        repeat(128) { bit ->
            if ((first[bit / 8].toInt() and (1 shl (7 - bit % 8))) != 0) {
                repeat(AES_BLOCK_SIZE) { product[it] = xorByte(product[it], factor[it]) }
            }
            val reduction = factor.last().toInt() and 1
            for (index in factor.lastIndex downTo 0) {
                val carry = if (index == 0) 0 else (factor[index - 1].toInt() and 1) shl 7
                factor[index] = ((factor[index].toInt() and 0xff) ushr 1 or carry).toByte()
            }
            if (reduction != 0) factor[0] = xorByte(factor[0], 0xe1.toByte())
        }
        factor.fill(0)
        return product
    }

    private fun incrementCounter(counter: ByteArray) {
        for (index in counter.lastIndex downTo counter.lastIndex - 3) {
            counter[index] = (counter[index] + 1).toByte()
            if (counter[index] != 0.toByte()) return
        }
    }

    private fun writeBitLength(output: ByteArray, offset: Int, byteLength: Int) {
        val bits = byteLength.toULong() * 8uL
        repeat(8) { index ->
            output[offset + 7 - index] = (bits shr (index * 8)).toByte()
        }
    }

    private fun constantTimeEquals(first: ByteArray, second: ByteArray): Boolean {
        if (first.size != second.size) return false
        var difference = 0
        first.indices.forEach { difference = difference or (first[it].toInt() xor second[it].toInt()) }
        return difference == 0
    }

    private fun xorByte(first: Byte, second: Byte): Byte =
        (first.toInt() xor second.toInt()).toByte()
}

private const val AES_128_KEY_SIZE = 16
private const val AES_BLOCK_SIZE = 16
private const val QUIC_NONCE_SIZE = 12
private const val GCM_TAG_SIZE = 16
