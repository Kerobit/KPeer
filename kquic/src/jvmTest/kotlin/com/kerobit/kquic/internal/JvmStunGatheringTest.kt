package com.kerobit.kquic.internal

import com.kerobit.kquic.ExperimentalKQuicApi
import com.kerobit.kquic.KQuicCandidateType
import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicContext
import com.kerobit.kquic.KQuicOffer
import com.kerobit.kquic.KQuicPeer
import com.kerobit.kquic.KQuicSecurity
import com.kerobit.kquic.KQuicStunServer
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalKQuicApi::class)
class JvmStunGatheringTest {
    @Test
    fun server_reflexive_candidate_reuses_the_host_candidate_socket() = runBlocking {
        val services = createKQuicPlatformServices(null)
        val advertisedAddress = services.hostAddresses().first()
        val server = DatagramSocket(0, InetAddress.getByName("0.0.0.0"))
        server.soTimeout = 5_000
        val bindingSourcePort = async(Dispatchers.IO) {
            val input = DatagramPacket(ByteArray(1_500), 1_500)
            server.receive(input)
            val request = input.data.copyOf(input.length)
            val message = StunProtocol.decode(request)
            assertEquals(StunProtocol.BINDING_REQUEST, message.type)
            val response = StunProtocol.bindingSuccessResponse(
                transactionId = message.transactionId,
                mappedAddress = KQuicSocketAddress(input.address.hostAddress, input.port),
                integrityPassword = null,
                hmacSha256 = JvmQuicCryptoPrimitives::hmacSha256
            )
            server.send(DatagramPacket(response, response.size, input.address, input.port))
            input.port
        }
        val peer = KQuicPeer(
            context = KQuicContext(scope = this),
            config = KQuicConfig(
                initiator = true,
                rendezvousId = "stun-gathering",
                security = KQuicSecurity.ephemeralWithAuthenticatedSignaling(),
                stunServers = listOf(KQuicStunServer(advertisedAddress, server.localPort))
            )
        )
        try {
            peer.start()
            val offer = withTimeout(5_000) {
                peer.signals.filterIsInstance<KQuicOffer>().first()
            }
            val host = assertNotNull(
                offer.candidates.firstOrNull { it.type == KQuicCandidateType.HOST }
            )
            val reflexive = assertNotNull(
                offer.candidates.firstOrNull {
                    it.type == KQuicCandidateType.SERVER_REFLEXIVE
                }
            )
            assertEquals(withTimeout(5_000) { bindingSourcePort.await() }, host.port)
            assertEquals(host.port, reflexive.relatedPort)
            assertEquals(host.host, reflexive.relatedHost)
        } finally {
            peer.close()
            withContext(Dispatchers.IO) { server.close() }
        }
    }
}
