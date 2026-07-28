package com.kerobit.kquic.internal

import com.kerobit.kquic.ExperimentalKQuicApi
import com.kerobit.kquic.KQuicChannelConfig
import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicContext
import com.kerobit.kquic.KQuicPeer
import com.kerobit.kquic.KQuicSecurity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalKQuicApi::class)
class KQuicNoIoBeforeStartTest {
    @Test
    fun facade_and_prepared_channels_do_not_bind_or_signal_before_start() = runBlocking {
        var bindCalls = 0
        val platform = createKQuicPlatformRuntime()
        val observedPlatform = object : KQuicPlatformRuntime by platform {
            override val udp = KQuicDatagramSocketFactory {
                bindCalls++
                error("UDP bind before start")
            }
        }
        val context = KQuicContext(
            scope = this,
            transportFactory = CommonKQuicTransportFactory(this, observedPlatform),
            platformRuntime = observedPlatform
        )
        val peer = KQuicPeer(
            context,
            KQuicConfig(
                initiator = true,
                rendezvousId = "no-io",
                security = KQuicSecurity.ephemeralWithAuthenticatedSignaling()
            )
        )

        peer.createChannel(KQuicChannelConfig("first"))
        peer.createChannel(KQuicChannelConfig("second"))

        assertEquals(0, bindCalls)
        assertNull(withTimeoutOrNull(50) { peer.signals.first() })
        peer.close()
        assertEquals(0, bindCalls)
    }
}
