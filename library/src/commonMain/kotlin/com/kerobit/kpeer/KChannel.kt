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
    public val data: Flow<ByteArray>

    public fun send(data: ByteArray): Boolean
    public fun close()
}

internal class KChannelImpl(
    override val label: String,
    private val connection: KPeerConnection,
    context: KPeerContext
) : KChannel {
    private val _state = MutableStateFlow(KChannelState.CONNECTING)

    override val state: Flow<KChannelState> = _state
    // Each KChannel projects the shared transport event stream down to its own label.
    override val data: Flow<ByteArray> = connection.events
        .filterIsInstance<KPeerTransportEvent.DataReceived>()
        .filter { it.label == label }
        .map { it.data }
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

    override fun send(data: ByteArray): Boolean = connection.sendRaw(label, data)

    override fun close() {
        _state.value = KChannelState.CLOSING
        connection.closeDataChannel(label)
    }
}
