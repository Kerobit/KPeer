package com.kerobit.kpeer

import kotlin.test.Test
import kotlin.test.assertEquals

class KPeerStatsTest {
    @Test
    fun `transport selected pair wins without double counting`() {
        val report = KPeerStatsReport(
            listOf(
                stat("transport", "transport", "selectedCandidatePairId" to str("pair-b"), "bytesSent" to num(900)),
                stat("pair-a", "candidate-pair", "nominated" to bool(true), "bytesSent" to num(100)),
                stat(
                    "pair-b",
                    "candidate-pair",
                    "state" to str("succeeded"),
                    "localCandidateId" to str("local"),
                    "remoteCandidateId" to str("remote"),
                    "bytesSent" to num(300),
                    "bytesReceived" to num(400),
                    "packetsSent" to num(30),
                    "packetsReceived" to num(40),
                    "currentRoundTripTime" to num(0.025),
                ),
                stat("local", "local-candidate", "candidateType" to str("host")),
                stat("remote", "remote-candidate", "candidateType" to str("srflx")),
            )
        )

        val result = report.toNetworkStats()

        assertEquals("pair-b", result.selectedCandidatePairId)
        assertEquals("direct", result.connectionMode)
        assertEquals(300L, result.bytesSent)
        assertEquals(400L, result.bytesReceived)
        assertEquals(30L, result.packetsSent)
        assertEquals(40L, result.packetsReceived)
        assertEquals(25L, result.rttMs)
    }

    @Test
    fun `nominated relay pair reports all selected pair bytes as relay`() {
        val report = KPeerStatsReport(
            listOf(
                stat(
                    "pair",
                    "candidate-pair",
                    "nominated" to bool(true),
                    "localCandidateId" to str("local"),
                    "remoteCandidateId" to str("remote"),
                    "bytesSent" to num(1_000),
                    "bytesReceived" to num(2_000),
                ),
                stat("local", "local-candidate", "candidateType" to str("relay")),
                stat("remote", "remote-candidate", "candidateType" to str("host")),
            )
        )

        val result = report.toNetworkStats()

        assertEquals("relay", result.connectionMode)
        assertEquals(3_000L, result.relayBytes)
    }

    @Test
    fun `cumulative rtt is averaged when current rtt is absent`() {
        val report = KPeerStatsReport(
            listOf(
                stat(
                    "pair",
                    "candidate-pair",
                    "state" to str("succeeded"),
                    "totalRoundTripTime" to num(1.2),
                    "responsesReceived" to num(4),
                )
            )
        )

        assertEquals(300L, report.toNetworkStats().rttMs)
    }

    @Test
    fun `transport counters are used when candidate pair is unavailable`() {
        val report = KPeerStatsReport(
            listOf(
                stat("transport", "transport", "bytesSent" to num(50), "bytesReceived" to num(75))
            )
        )

        val result = report.toNetworkStats()

        assertEquals("", result.connectionMode)
        assertEquals(50L, result.bytesSent)
        assertEquals(75L, result.bytesReceived)
    }

    private fun stat(id: String, type: String, vararg values: Pair<String, KPeerStatValue>) =
        KPeerStat(id, type, timestampUs = 0, values = mapOf(*values))

    private fun str(value: String) = KPeerStatValue.Str(value)
    private fun num(value: Number) = KPeerStatValue.Num(value.toDouble())
    private fun bool(value: Boolean) = KPeerStatValue.Bool(value)
}
