package com.kerobit.kpeer

import com.kerobit.kpeer.internal.KPeerConnection
import com.kerobit.kpeer.internal.KPeerTransportEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow

/** Configuration for a single WebRTC data channel. */
public data class ChannelConfig(
    public val label: String,
    public val ordered: Boolean = true,
    public val reliable: Boolean = true
)

public enum class KChannelState {
    CONNECTING,
    OPEN,
    CLOSING,
    CLOSED
}

/** Data channel wrapper exposed by KPeer. */
public interface KChannel {
    public val label: String
    public val state: Flow<KChannelState>
    /** Emits binary payloads received on this channel only. */
    public val bytes: Flow<ByteArray>
    /** Emits text payloads received on this channel only. */
    public val text: Flow<String>

    public fun send(bytes: ByteArray): Boolean
    public fun send(text: String): Boolean
    /** Registers a callback for binary messages received on this channel. */
    public fun onBytes(handler: (ByteArray) -> Unit): KSubscription
    /** Registers a callback for text messages received on this channel. */
    public fun onText(handler: (String) -> Unit): KSubscription
    /** Registers a callback for channel state changes. */
    public fun onState(handler: (KChannelState) -> Unit): KSubscription
    public fun close()
}

internal class KChannelImpl(
    private val context: KPeerContext,
    override val label: String,
    private val connection: KPeerConnection
) : KChannel {
    private val _state = MutableStateFlow(KChannelState.CONNECTING)

    override val state: Flow<KChannelState> = _state
    // Each KChannel projects the shared transport event stream down to its own label.
    override val bytes: Flow<ByteArray> = connection.events
        .filterIsInstance<KPeerTransportEvent.BytesReceived>()
        .filter { it.label == label }
        .map { it.data }
        .shareIn(context.scope, SharingStarted.WhileSubscribed(), replay = 0)
    override val text: Flow<String> = connection.events
        .filterIsInstance<KPeerTransportEvent.TextReceived>()
        .filter { it.label == label }
        .map { it.text }
        .shareIn(context.scope, SharingStarted.WhileSubscribed(), replay = 0)

    init {
        context.scope.launch {
            connection.events.collect { event ->
                when (event) {
                    is KPeerTransportEvent.DataChannelOpen -> if (event.label == label) {
                        _state.value = KChannelState.OPEN
                    }
                    is KPeerTransportEvent.DataChannelClosed -> if (event.label == label) {
                        _state.value = KChannelState.CLOSED
                    }
                    else -> Unit
                }
            }
        }
    }

    override fun send(bytes: ByteArray): Boolean = connection.sendRaw(label, bytes)

    override fun send(text: String): Boolean = connection.sendTextRaw(label, text)

    override fun onBytes(handler: (ByteArray) -> Unit): KSubscription {
        val job = context.scope.launch {
            bytes.collect(handler)
        }
        return KSubscription { job.cancel() }
    }

    override fun onText(handler: (String) -> Unit): KSubscription {
        val job = context.scope.launch {
            text.collect(handler)
        }
        return KSubscription { job.cancel() }
    }

    override fun onState(handler: (KChannelState) -> Unit): KSubscription {
        val job = context.scope.launch {
            state.collect(handler)
        }
        return KSubscription { job.cancel() }
    }

    override fun close() {
        _state.value = KChannelState.CLOSING
        connection.closeDataChannel(label)
    }
}
