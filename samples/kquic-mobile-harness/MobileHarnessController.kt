@file:OptIn(com.kerobit.kquic.ExperimentalKQuicApi::class)

package com.kerobit.samples.kquic

import com.kerobit.kquic.KQuicChannelConfig
import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicContext
import com.kerobit.kquic.KQuicDelivery
import com.kerobit.kquic.KQuicPeer
import com.kerobit.kquic.KQuicSecurity
import com.kerobit.kquic.KQuicSignal
import com.kerobit.kquic.KQuicStats
import com.kerobit.kquic.KQuicStunServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared controller used by the manual Android/iOS harness.
 *
 * The host UI only needs to encode/decode [KQuicSignal] through its authenticated signaling
 * channel and render [lines] plus [stats].
 */
class MobileHarnessController(
    scope: CoroutineScope,
    platformContext: Any?,
    initiator: Boolean,
    roomId: String,
    stunServers: List<KQuicStunServer>,
    private val sendSignal: suspend (KQuicSignal) -> Unit
) {
    private val mutableLines = MutableStateFlow<List<String>>(emptyList())
    private val mutableStats = MutableStateFlow(KQuicStats())
    val lines: StateFlow<List<String>> = mutableLines.asStateFlow()
    val stats: StateFlow<KQuicStats> = mutableStats.asStateFlow()

    private val peer = KQuicPeer(
        context = KQuicContext(scope, platformContext),
        config = KQuicConfig(
            initiator = initiator,
            rendezvousId = roomId,
            security = KQuicSecurity.ephemeralWithAuthenticatedSignaling(),
            stunServers = stunServers
        )
    )

    init {
        peer.onSignal { signal ->
            scope.launch {
                runCatching { sendSignal(signal) }
                    .onFailure { append("signaling failed: ${it.message}") }
            }
        }
        peer.onConnectionState { append("state=$it") }
        peer.onStats { mutableStats.value = it }
        peer.onChannel { channel ->
            append("incoming channel=${channel.label} delivery=${channel.delivery}")
            channel.onBytes { append("${channel.label}: ${it.size} bytes") }
            channel.onText { append("${channel.label}: $it") }
        }
    }

    fun start() = peer.start()

    suspend fun receiveSignal(signal: KQuicSignal) = peer.signal(signal)

    fun sendReliable(size: Int = 16_384): Boolean {
        val channel = peer.createChannel(
            KQuicChannelConfig("reliable", KQuicDelivery.RELIABLE_ORDERED)
        )
        return channel.send(ByteArray(size) { (it and 0xff).toByte() }).also {
            append("reliable accepted=$it bytes=$size")
        }
    }

    fun sendUnreliable(text: String): Boolean {
        val channel = peer.createChannel(
            KQuicChannelConfig("unreliable", KQuicDelivery.UNRELIABLE)
        )
        return channel.send(text).also { append("unreliable accepted=$it") }
    }

    fun close() = peer.close()

    private fun append(line: String) {
        mutableLines.value = (mutableLines.value + line).takeLast(200)
    }
}
