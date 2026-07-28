package com.kerobit.kquic.internal

import com.kerobit.kquic.ExperimentalKQuicApi
import com.kerobit.kquic.KQuicAnswer
import com.kerobit.kquic.KQuicChannelConfig
import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicContext
import com.kerobit.kquic.KQuicOffer
import com.kerobit.kquic.KQuicPeer
import com.kerobit.kquic.KQuicPeerConnectionState
import com.kerobit.kquic.KQuicSecurity
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalKQuicApi::class)
class AppleKQuicBidirectionalTest {
    @Test
    fun reliable_channels_keep_exchanging_bidirectional_messages_after_handshake() = runBlocking {
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
            initiator.signal(withTimeout(5_000) { answer.await() })
            withTimeout(10_000) {
                initiator.connectionState.first { it == KQuicPeerConnectionState.CONNECTED }
            }
            withTimeout(10_000) {
                responder.connectionState.first { it == KQuicPeerConnectionState.CONNECTED }
            }

            val responderControl = async(start = CoroutineStart.UNDISPATCHED) {
                responder.channels.first { it.label == "control" }
            }
            val responderData = async(start = CoroutineStart.UNDISPATCHED) {
                responder.channels.first { it.label == "data" }
            }
            val initiatorControl = initiator.createChannel(KQuicChannelConfig("control"))
            val initiatorData = initiator.createChannel(KQuicChannelConfig("data"))
            val remoteControl = withTimeout(5_000) { responderControl.await() }
            val remoteData = withTimeout(5_000) { responderData.await() }
            val atInitiator = Channel<ByteArray>(Channel.UNLIMITED)
            val atResponder = Channel<ByteArray>(Channel.UNLIMITED)
            val collectors = listOf(
                launch(start = CoroutineStart.UNDISPATCHED) {
                    initiatorControl.bytes.collect(atInitiator::send)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    remoteControl.bytes.collect(atResponder::send)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    initiatorData.bytes.collect(atInitiator::send)
                },
                launch(start = CoroutineStart.UNDISPATCHED) {
                    remoteData.bytes.collect(atResponder::send)
                },
            )

            repeat(4) { sequence ->
                val fromInitiator = "initiator-$sequence".encodeToByteArray()
                val fromResponder = "responder-$sequence".encodeToByteArray()
                assertTrue(initiatorControl.send(fromInitiator))
                assertTrue(remoteControl.send(fromResponder))
                assertContentEquals(fromInitiator, withTimeout(3_000) { atResponder.receive() })
                assertContentEquals(fromResponder, withTimeout(3_000) { atInitiator.receive() })
                assertTrue(initiatorData.send(fromInitiator))
                assertTrue(remoteData.send(fromResponder))
                assertContentEquals(fromInitiator, withTimeout(3_000) { atResponder.receive() })
                assertContentEquals(fromResponder, withTimeout(3_000) { atInitiator.receive() })
                delay(2_000)
            }
            collectors.forEach { it.cancel() }
        } finally {
            initiator.close()
            responder.close()
        }
    }

    private fun kotlinx.coroutines.CoroutineScope.peer(initiator: Boolean) = KQuicPeer(
        context = KQuicContext(scope = this),
        config = KQuicConfig(
            initiator = initiator,
            rendezvousId = "apple-bidirectional-test",
            security = KQuicSecurity.ephemeralWithAuthenticatedSignaling(),
            connectionTimeoutMs = 20_000,
            connectivityCheckTimeoutMs = 2_000,
        ),
    )
}
