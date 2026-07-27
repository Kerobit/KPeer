package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicConnectivityCredentials
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StunProtocolTest {
    private val hmac: (ByteArray, ByteArray) -> ByteArray = { key, data ->
        TestSha256.hmac(key, data)
    }

    @Test
    fun authenticated_connectivity_request_roundTrips() {
        val local = KQuicConnectivityCredentials("local-user", "local-password-123")
        val remote = KQuicConnectivityCredentials("remote-user", "remote-password-123")
        val transaction = ByteArray(12) { it.toByte() }
        val encoded = StunProtocol.connectivityRequest(
            transaction,
            local,
            remote,
            controlling = true,
            useCandidate = true,
            priority = 100,
            tieBreaker = 7uL,
            hmacSha256 = hmac
        )
        val decoded = StunProtocol.decode(encoded, remote.password, hmac)
        assertEquals(StunProtocol.BINDING_REQUEST, decoded.type)
        assertTrue(decoded.integrityValid == true)
        assertEquals(
            "remote-user:local-user",
            decoded.attribute(StunProtocol.USERNAME)?.decodeToString()
        )
        assertTrue(decoded.attribute(StunProtocol.USE_CANDIDATE) != null)
        assertFalse(
            StunProtocol.decode(encoded, "incorrect-password", hmac).integrityValid == true
        )
    }

    @Test
    fun xorMappedAddress_roundTrips_ipv4_and_ipv6() {
        val transaction = ByteArray(12) { (it + 1).toByte() }
        listOf(
            KQuicSocketAddress("192.0.2.25", 54321) to "192.0.2.25",
            KQuicSocketAddress("2001:db8::1", 3478) to "2001:db8:0:0:0:0:0:1"
        ).forEach { (address, canonicalHost) ->
            val response = StunProtocol.bindingSuccessResponse(
                transaction,
                address,
                integrityPassword = null,
                hmacSha256 = hmac
            )
            assertEquals(
                KQuicSocketAddress(canonicalHost, address.port),
                StunProtocol.mappedAddress(StunProtocol.decode(response))
            )
        }
    }
}

/** Tiny deterministic SHA-256/HMAC test helper supplied by the platform-independent test source. */
private object TestSha256 {
    fun hmac(key: ByteArray, data: ByteArray): ByteArray {
        val block = 64
        val normalized = if (key.size > block) sha256(key) else key
        val padded = normalized.copyOf(block)
        val outer = ByteArray(block) { (padded[it].toInt() xor 0x5c).toByte() }
        val inner = ByteArray(block) { (padded[it].toInt() xor 0x36).toByte() }
        return sha256(outer + sha256(inner + data))
    }

    private fun sha256(input: ByteArray): ByteArray {
        val initial = intArrayOf(
            0x6a09e667, 0xbb67ae85.toInt(), 0x3c6ef372, 0xa54ff53a.toInt(),
            0x510e527f, 0x9b05688c.toInt(), 0x1f83d9ab, 0x5be0cd19
        )
        val constants = SHA256_CONSTANTS
        val bitLength = input.size.toLong() * 8
        val paddedSize = ((input.size + 9 + 63) / 64) * 64
        val padded = ByteArray(paddedSize)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        repeat(8) { index ->
            padded[padded.lastIndex - index] = (bitLength ushr (index * 8)).toByte()
        }
        val words = IntArray(64)
        for (offset in padded.indices step 64) {
            repeat(16) { index ->
                val at = offset + index * 4
                words[index] =
                    ((padded[at].toInt() and 0xff) shl 24) or
                        ((padded[at + 1].toInt() and 0xff) shl 16) or
                        ((padded[at + 2].toInt() and 0xff) shl 8) or
                        (padded[at + 3].toInt() and 0xff)
            }
            for (index in 16 until 64) {
                val a = words[index - 15]
                val b = words[index - 2]
                val s0 = a.rotateRight(7) xor a.rotateRight(18) xor (a ushr 3)
                val s1 = b.rotateRight(17) xor b.rotateRight(19) xor (b ushr 10)
                words[index] = words[index - 16] + s0 + words[index - 7] + s1
            }
            var a = initial[0]
            var b = initial[1]
            var c = initial[2]
            var d = initial[3]
            var e = initial[4]
            var f = initial[5]
            var g = initial[6]
            var h = initial[7]
            repeat(64) { index ->
                val sum1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val choice = (e and f) xor (e.inv() and g)
                val temp1 = h + sum1 + choice + constants[index] + words[index]
                val sum0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val majority = (a and b) xor (a and c) xor (b and c)
                val temp2 = sum0 + majority
                h = g; g = f; f = e; e = d + temp1
                d = c; c = b; b = a; a = temp1 + temp2
            }
            initial[0] += a; initial[1] += b; initial[2] += c; initial[3] += d
            initial[4] += e; initial[5] += f; initial[6] += g; initial[7] += h
        }
        return ByteArray(32) { index ->
            (initial[index / 4] ushr ((3 - index % 4) * 8)).toByte()
        }
    }
}

private val SHA256_CONSTANTS = intArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(),
    0x3956c25b, 0x59f111f1, 0x923f82a4.toInt(), 0xab1c5ed5.toInt(),
    0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(),
    0xe49b69c1.toInt(), 0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc,
    0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(),
    0xc6e00bf3.toInt(), 0xd5a79147.toInt(), 0x06ca6351, 0x14292967,
    0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(),
    0xa2bfe8a1.toInt(), 0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(),
    0xd192e819.toInt(), 0xd6990624.toInt(), 0xf40e3585.toInt(), 0x106aa070,
    0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5,
    0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
    0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
    0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt()
)
