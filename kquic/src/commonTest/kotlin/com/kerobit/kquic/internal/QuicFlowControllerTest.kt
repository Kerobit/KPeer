package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicSecurity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QuicFlowControllerTest {
    @Test
    fun pending_writes_drain_when_connection_and_stream_credit_are_available() {
        val frames = mutableListOf<QuicFrame>()
        val drained = mutableListOf<Pair<String, Int>>()
        val controller = controller(frames, drained)
        controller.applyRemoteParameters(parameters(maxData = 64uL, maxStreamData = 64uL))
        val reservation = assertNotNull(controller.reserve(4))

        controller.queue(
            streamId = 0uL,
            label = "game",
            bytes = byteArrayOf(1, 2, 3, 4),
            reservedBytes = reservation,
            bufferedBytes = 4
        )

        assertIs<QuicFrame.Stream>(frames.single())
        assertEquals(listOf("game" to 4), drained)
    }

    @Test
    fun blocked_writes_emit_credit_signals_and_respect_queue_limit() {
        val frames = mutableListOf<QuicFrame>()
        val controller = controller(frames, mutableListOf())
        controller.applyRemoteParameters(parameters(maxData = 0uL, maxStreamData = 0uL))
        controller.queue(0uL, "blocked", byteArrayOf(1))

        assertEquals(1, controller.streamsBlocked)
        assertIs<QuicFrame.DataBlocked>(frames[0])
        assertIs<QuicFrame.StreamDataBlocked>(frames[1])
        assertNull(controller.reserve(2_000_000))
    }

    @Test
    fun receive_credit_is_accounted_per_stream_and_connection() {
        val controller = controller(mutableListOf(), mutableListOf())

        val first = controller.consume(0uL, 10)
        val second = controller.consume(4uL, 5)

        assertEquals(1_048_586uL, first.connectionMaximum)
        assertEquals(262_154uL, first.streamMaximum)
        assertEquals(1_048_591uL, second.connectionMaximum)
        assertEquals(262_149uL, second.streamMaximum)
    }

    private fun controller(
        frames: MutableList<QuicFrame>,
        drained: MutableList<Pair<String, Int>>
    ) = QuicFlowController(
        endpointRole = QuicEndpointRole.CLIENT,
        config = KQuicConfig(
            initiator = true,
            rendezvousId = "flow",
            security = KQuicSecurity.ephemeralWithAuthenticatedSignaling()
        ),
        queuedFrameBytes = { 0 },
        enqueue = { frame, _ -> frames.add(frame) },
        bufferedAmountDrained = { label, bytes -> drained += label to bytes }
    )

    private fun parameters(maxData: ULong, maxStreamData: ULong) =
        QuicTransportParameterValues(
            initialMaxData = maxData,
            initialMaxStreamDataBidiLocal = maxStreamData,
            initialMaxStreamDataBidiRemote = maxStreamData,
            initialMaxStreamDataUni = maxStreamData,
            initialMaxStreamsBidi = 8uL,
            initialMaxStreamsUni = 8uL,
            initialSourceConnectionId = byteArrayOf(1)
        )
}
