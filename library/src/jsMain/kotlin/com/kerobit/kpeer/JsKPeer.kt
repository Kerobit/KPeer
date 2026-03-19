@file:OptIn(kotlin.js.ExperimentalJsExport::class)

package com.kerobit.kpeer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlin.js.Json
import kotlin.js.json

/**
 * Small JS-friendly wrapper around [KPeer].
 *
 * - Uses callbacks instead of Flow.
 * - Represents signals as plain JS objects:
 *   - { type: "offer", sdp: "..." }
 *   - { type: "answer", sdp: "..." }
 *   - { type: "ice", candidate: "...", sdpMid: "...", sdpMLineIndex: 0 }
 */
@JsExport
public class JsKPeer(
    private val peer: KPeer,
    private val scope: CoroutineScope = MainScope()
) {
    public fun onSignal(handler: (Json) -> Unit) {
        peer.onSignal { s -> handler(signalToJson(s)) }
    }

    public fun onConnectionState(handler: (String) -> Unit) {
        peer.onConnectionState { st -> handler(st.name.lowercase()) }
    }

    public fun onChannel(handler: (JsKChannel) -> Unit) {
        peer.onChannel { ch -> handler(JsKChannel(ch, scope)) }
    }

    public fun signal(signal: Json) {
        scope.launch {
            peer.signal(jsonToSignal(signal))
        }
    }

    /**
     * Creates a channel on this peer.
     *
     * The created channel will be delivered via [onChannel]. This method is fire-and-forget.
     */
    public fun createChannel(label: String) {
        scope.launch {
            peer.createChannel(ChannelConfig(label = label))
        }
    }

    public fun close() {
        peer.close()
    }

    public fun dispose() {
        peer.dispose()
    }

    /**
     * Gets stats as a JSON string (portable for JS consumers).
     *
     * Note: this is intentionally callback-based (not suspend) to keep JS interop simple.
     */
    public fun getStatsJson(handler: (String) -> Unit) {
        scope.launch {
            val report = peer.getStats()
            handler(statsReportToJson(report))
        }
    }
}

@JsExport
public class JsKChannel(
    private val channel: KChannel,
    private val scope: CoroutineScope
) {
    public val label: String get() = channel.label

    public fun onText(handler: (String) -> Unit) {
        channel.onText(handler)
    }

    public fun sendText(text: String): Boolean = channel.send(text)

    public fun bufferedAmount(): Long = channel.currentBufferedAmount

    public fun close() {
        channel.close()
    }
}

@JsExport
public object KPeerJs {
    /**
     * Creates a JS-friendly peer instance.
     *
     * Pass `iceServers` as an array of objects:
     * - { url: "stun:...", username: "...", credential: "..." }
     */
    public fun createPeer(
        initiator: Boolean,
        iceServers: Array<dynamic> = emptyArray(),
        flushInterval: Long = 50L,
        maxBatchSize: Int? = null,
        bufferRemoteIceUntilDescription: Boolean = true
    ): JsKPeer {
        val servers = iceServers.mapNotNull { s ->
            val url = (s.url as? String) ?: return@mapNotNull null
            IceServer(
                url = url,
                username = s.username as? String,
                credential = s.credential as? String
            )
        }
        val config = KPeerConfig(
            initiator = initiator,
            iceServers = if (servers.isEmpty()) KPeerConfig.defaultIceServers() else servers,
            signaling = SignalingConfig(
                flushInterval = flushInterval,
                maxBatchSize = maxBatchSize,
                bufferRemoteIceUntilDescription = bufferRemoteIceUntilDescription
            )
        )
        val peer = KPeer(
            context = KPeerContext(scope = MainScope(), platformContext = null),
            config = config
        )
        return JsKPeer(peer)
    }
}

private fun signalToJson(signal: KPeerSignal): Json = when (signal) {
    is KPeerSignal.Offer -> json("type" to "offer", "sdp" to signal.sdp)
    is KPeerSignal.Answer -> json("type" to "answer", "sdp" to signal.sdp)
    is KPeerSignal.IceCandidate -> json(
        "type" to "ice",
        "candidate" to signal.candidate,
        "sdpMid" to signal.sdpMid,
        "sdpMLineIndex" to signal.sdpMLineIndex
    )
}

private fun jsonToSignal(obj: Json): KPeerSignal {
    val type = obj["type"] as? String ?: error("signal.type missing")
    return when (type) {
        "offer" -> KPeerSignal.Offer(sdp = obj["sdp"] as String)
        "answer" -> KPeerSignal.Answer(sdp = obj["sdp"] as String)
        "ice" -> KPeerSignal.IceCandidate(
            candidate = obj["candidate"] as String,
            sdpMid = obj["sdpMid"] as? String,
            sdpMLineIndex = (obj["sdpMLineIndex"] as? Number)?.toInt()
        )
        else -> error("Unknown signal.type=$type")
    }
}

private fun statsReportToJson(report: KPeerStatsReport): String {
    val statsJson = report.stats.joinToString(
        prefix = "[",
        postfix = "]",
        separator = ","
    ) { stat ->
        val valuesJson = stat.values.entries.joinToString(
            prefix = "{",
            postfix = "}",
            separator = ","
        ) { (k, v) ->
            jsonString(k) + ":" + statValueToJson(v)
        }
        "{" +
            "\"id\":" + jsonString(stat.id) + "," +
            "\"type\":" + jsonString(stat.type) + "," +
            "\"timestampUs\":" + stat.timestampUs + "," +
            "\"values\":" + valuesJson +
            "}"
    }
    return statsJson
}

private fun statValueToJson(v: KPeerStatValue): String = when (v) {
    is KPeerStatValue.Str -> jsonString(v.value)
    is KPeerStatValue.Num -> {
        val d = v.value
        if (d.isNaN() || d.isInfinite()) "null" else d.toString()
    }
    is KPeerStatValue.Bool -> v.value.toString()
    KPeerStatValue.Null -> "null"
}

private fun jsonString(s: String): String {
    val escaped = buildString(s.length + 16) {
        for (ch in s) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(ch)
            }
        }
    }
    return "\"" + escaped + "\""
}

