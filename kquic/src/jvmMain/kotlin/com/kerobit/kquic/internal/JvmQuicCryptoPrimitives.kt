package com.kerobit.kquic.internal

import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

internal object JvmQuicCryptoPrimitives : QuicCryptoPrimitives {
    override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    override fun aesEcbEncrypt(key: ByteArray, block: ByteArray): ByteArray =
        Cipher.getInstance("AES/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            doFinal(block)
        }

    override fun aesGcmSeal(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        plaintext: ByteArray
    ): ByteArray = aesGcm(Cipher.ENCRYPT_MODE, key, nonce, aad, plaintext)

    override fun aesGcmOpen(
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        ciphertext: ByteArray
    ): ByteArray = aesGcm(Cipher.DECRYPT_MODE, key, nonce, aad, ciphertext)

    private fun aesGcm(
        mode: Int,
        key: ByteArray,
        nonce: ByteArray,
        aad: ByteArray,
        input: ByteArray
    ): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
        init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
        updateAAD(aad)
        doFinal(input)
    }
}
