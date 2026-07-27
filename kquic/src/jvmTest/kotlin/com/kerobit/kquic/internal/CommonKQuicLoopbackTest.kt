package com.kerobit.kquic.internal

import com.kerobit.kquic.ExperimentalKQuicApi
import com.kerobit.kquic.KQuicAnswer
import com.kerobit.kquic.KQuicChannelConfig
import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicConnectivityCredentials
import com.kerobit.kquic.KQuicContext
import com.kerobit.kquic.KQuicDelivery
import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicOffer
import com.kerobit.kquic.KQuicPeer
import com.kerobit.kquic.KQuicPeerConnectionState
import com.kerobit.kquic.KQuicSecurity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(ExperimentalKQuicApi::class)
class CommonKQuicLoopbackTest {
    @Test
    fun real_udp_transport_connects_and_exchanges_reliable_bytes() = runBlocking {
        val initiator = peer(true)
        val responder = peer(false)
        val initiatorStateTrace = mutableListOf<KQuicPeerConnectionState>()
        val responderStateTrace = mutableListOf<KQuicPeerConnectionState>()
        val initiatorStateSubscription = initiator.onConnectionState(initiatorStateTrace::add)
        val responderStateSubscription = responder.onConnectionState(responderStateTrace::add)
        try {
            val offer = async(start = CoroutineStart.UNDISPATCHED) {
                initiator.signals.filterIsInstance<KQuicOffer>().first()
            }
            initiator.start()
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                responder.signals.filterIsInstance<KQuicAnswer>().first()
            }
            responder.signal(withTimeout(5_000) { offer.await() })
            initiator.signal(withTimeout(5_000) { answer.await() })

            val initiatorConnected = withTimeoutOrNull(15_000) {
                initiator.connectionState.first { it == KQuicPeerConnectionState.CONNECTED }
            }
            check(initiatorConnected != null) {
                "Initiator states=$initiatorStateTrace, responder states=$responderStateTrace, " +
                    "initiator stats=${initiator.stats.first()}, responder stats=${responder.stats.first()}"
            }
            withTimeout(10_000) {
                responder.connectionState.first { it == KQuicPeerConnectionState.CONNECTED }
            }

            val incomingChannel = async(start = CoroutineStart.UNDISPATCHED) {
                responder.channels.first()
            }
            val outbound = initiator.createChannel(KQuicChannelConfig("reliable"))
            val inbound = withTimeout(5_000) { incomingChannel.await() }
            val received = async(start = CoroutineStart.UNDISPATCHED) { inbound.bytes.first() }
            assertTrue(outbound.send(byteArrayOf(1, 2, 3, 4)))
            assertContentEquals(
                byteArrayOf(1, 2, 3, 4),
                withTimeout(5_000) { received.await() }
            )

            val fragmentedIncomingChannel = async(start = CoroutineStart.UNDISPATCHED) {
                responder.channels.first { it.label == "fragmented" }
            }
            val fragmentedOutbound = initiator.createChannel(
                KQuicChannelConfig("fragmented")
            )
            val fragmentedInbound = withTimeout(5_000) { fragmentedIncomingChannel.await() }
            val fragmentedPayload = ByteArray(8_192) { (it and 0xff).toByte() }
            val fragmentedReceived = async(start = CoroutineStart.UNDISPATCHED) {
                fragmentedInbound.bytes.first()
            }
            assertTrue(fragmentedOutbound.send(fragmentedPayload))
            assertContentEquals(
                fragmentedPayload,
                withTimeout(10_000) { fragmentedReceived.await() }
            )

            val datagramIncomingChannel = async(start = CoroutineStart.UNDISPATCHED) {
                responder.channels.first { it.label == "datagram" }
            }
            val datagramOutbound = initiator.createChannel(
                KQuicChannelConfig("datagram", KQuicDelivery.UNRELIABLE)
            )
            val datagramInbound = withTimeout(5_000) { datagramIncomingChannel.await() }
            val datagramReceived = async(start = CoroutineStart.UNDISPATCHED) {
                datagramInbound.text.first()
            }
            assertTrue(datagramOutbound.send("hello over quic datagram"))
            assertFailsWith<KQuicError.MessageTooLarge> {
                datagramOutbound.send(ByteArray(1_101))
            }
            assertContentEquals(
                "hello over quic datagram".encodeToByteArray(),
                withTimeout(5_000) { datagramReceived.await() }.encodeToByteArray()
            )

            val remoteClosed = async(start = CoroutineStart.UNDISPATCHED) {
                responder.connectionState.first {
                    it == KQuicPeerConnectionState.DISCONNECTED
                }
            }
            initiator.close()
            assertEquals(
                KQuicPeerConnectionState.DISCONNECTED,
                withTimeout(5_000) { remoteClosed.await() }
            )
            assertEquals("application_close", responder.stats.first().closeReason)
        } finally {
            initiatorStateSubscription.cancel()
            responderStateSubscription.cancel()
            initiator.close()
            responder.close()
        }
    }

    @Test
    fun authenticated_signaling_rejects_a_tampered_identity_fingerprint() = runBlocking {
        val initiator = peer(true)
        val responder = peer(false)
        try {
            val offer = async(start = CoroutineStart.UNDISPATCHED) {
                initiator.signals.filterIsInstance<KQuicOffer>().first()
            }
            initiator.start()
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                responder.signals.filterIsInstance<KQuicAnswer>().first()
            }
            responder.signal(withTimeout(5_000) { offer.await() })
            val genuineAnswer = withTimeout(5_000) { answer.await() }
            initiator.signal(
                genuineAnswer.copy(identityPublicKeySha256 = "00".repeat(32))
            )

            assertEquals(
                KQuicPeerConnectionState.FAILED,
                withTimeout(10_000) {
                    initiator.connectionState.first {
                        it == KQuicPeerConnectionState.FAILED
                    }
                }
            )
            assertTrue(
                initiator.stats.first().closeReason.orEmpty().contains(
                    "fingerprint",
                    ignoreCase = true
                )
            )
        } finally {
            initiator.close()
            responder.close()
        }
    }

    @Test
    fun connectivity_checks_reject_tampered_credentials_and_time_out() = runBlocking {
        val initiator = peer(true, connectivityTimeoutMs = 1_000)
        val responder = peer(false, connectivityTimeoutMs = 1_000)
        try {
            val offer = async(start = CoroutineStart.UNDISPATCHED) {
                initiator.signals.filterIsInstance<KQuicOffer>().first()
            }
            initiator.start()
            val answer = async(start = CoroutineStart.UNDISPATCHED) {
                responder.signals.filterIsInstance<KQuicAnswer>().first()
            }
            responder.signal(withTimeout(5_000) { offer.await() })
            val genuineAnswer = withTimeout(5_000) { answer.await() }
            initiator.signal(
                genuineAnswer.copy(
                    connectivityCredentials = KQuicConnectivityCredentials(
                        genuineAnswer.connectivityCredentials.usernameFragment,
                        "incorrect-password"
                    )
                )
            )

            withTimeout(5_000) {
                initiator.connectionState.first { it == KQuicPeerConnectionState.FAILED }
            }
            assertTrue(
                initiator.stats.first().closeReason.orEmpty().contains(
                    "connectivity checks",
                    ignoreCase = true
                )
            )
        } finally {
            initiator.close()
            responder.close()
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.peer(
        initiator: Boolean,
        connectivityTimeoutMs: Long = 2_000
    ) = KQuicPeer(
        context = KQuicContext(scope = this),
        config = KQuicConfig(
            initiator = initiator,
            rendezvousId = "loopback-test",
            security = KQuicSecurity.ephemeralWithAuthenticatedSignaling(),
            connectionTimeoutMs = 20_000,
            connectivityCheckTimeoutMs = connectivityTimeoutMs
        )
    )
}
