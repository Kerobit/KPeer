package com.kerobit.kpeer.internal.nativeP2P

import com.kerobit.kpeer.ChannelConfig
import com.kerobit.kpeer.KPeerStatValue
import kotlin.test.Test
import kotlin.test.assertEquals

class NativeP2PHelpersTest {

    @Test
    fun toStatValue_maps_basic_types() {
        assertEquals(KPeerStatValue.Null, toStatValue(null))
        assertEquals(KPeerStatValue.Str("abc"), toStatValue("abc"))
        assertEquals(KPeerStatValue.Bool(true), toStatValue(true))
        assertEquals(KPeerStatValue.Num(1.0), toStatValue(1))

        data class Foo(val a: Int)
        assertEquals(KPeerStatValue.Str(Foo(1).toString()), toStatValue(Foo(1)))
    }

    @Test
    fun channelConfig_toControlParams_sets_retransmits_only_when_unreliable() {
        val reliable = ChannelConfig(label = "ch1", ordered = true, reliable = true)
        val reliableParams = reliable.toControlParams()
        assertEquals(true, reliableParams.ordered)
        assertEquals(null, reliableParams.maxRetransmitsOrNull)

        val unreliable = ChannelConfig(label = "ch2", ordered = false, reliable = false)
        val unreliableParams = unreliable.toControlParams()
        assertEquals(false, unreliableParams.ordered)
        assertEquals(0, unreliableParams.maxRetransmitsOrNull)
    }

    @Test
    fun iceCandidateBuffer_queues_until_mark_then_flushes_in_order() {
        val received = mutableListOf<Int>()
        val buffer = IceCandidateBuffer<Int>()

        buffer.queueOrAdd(1) { received.add(it) }
        buffer.queueOrAdd(2) { received.add(it) }
        assertEquals(emptyList<Int>(), received)

        buffer.markRemoteDescriptionSetAndFlush { received.add(it) }
        assertEquals(listOf(1, 2), received)

        buffer.queueOrAdd(3) { received.add(it) }
        assertEquals(listOf(1, 2, 3), received)
    }

    @Test
    fun iceCandidateBuffer_reset_clears_queue() {
        val received = mutableListOf<Int>()
        val buffer = IceCandidateBuffer<Int>()

        buffer.queueOrAdd(1) { received.add(it) }
        buffer.reset()
        buffer.markRemoteDescriptionSetAndFlush { received.add(it) }

        assertEquals(emptyList<Int>(), received)
    }
}

