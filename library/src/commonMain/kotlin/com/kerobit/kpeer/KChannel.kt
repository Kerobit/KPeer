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

public enum class KChannelState {
    CONNECTING,
    OPEN,
    CLOSING,
    CLOSED
}

/** Data channel wrapper exposed by KPeer. */
public class KChannel internal constructor(
    private val context: KPeerContext,
    public val label: String,
    private val connection: KPeerConnection
) {
    private val _state = MutableStateFlow(KChannelState.CONNECTING)
    private val _bufferedAmount = MutableStateFlow(0L)

    public val state: Flow<KChannelState> = _state
    /** Number of bytes currently queued for sending on the underlying transport. */
    public val bufferedAmount: Flow<Long> = _bufferedAmount
    /** Latest known buffered amount value. */
    public val currentBufferedAmount: Long
        get() = _bufferedAmount.value
    // Each KChannel projects the shared transport event stream down to its own label.
    /** Emits binary payloads received on this channel only. */
    public val bytes: Flow<ByteArray> = connection.events
        .filterIsInstance<KPeerTransportEvent.BytesReceived>()
        .filter { it.label == label }
        .map { it.data }
        .shareIn(context.scope, SharingStarted.WhileSubscribed(), replay = 0)
    /** Emits text payloads received on this channel only. */
    public val text: Flow<String> = connection.events
        .filterIsInstance<KPeerTransportEvent.TextReceived>()
        .filter { it.label == label }
        .map { it.text }
        .shareIn(context.scope, SharingStarted.WhileSubscribed(), replay = 0)

    init {
        fun attachBufferedAmountIfPossible() {
            val native = connection.getDataChannel(label) ?: return
            context.scope.launch {
                native.bufferedAmount.collect { amount ->
                    _bufferedAmount.value = amount
                }
            }
            _bufferedAmount.value = native.currentBufferedAmount
        }

        attachBufferedAmountIfPossible()
        context.scope.launch {
            connection.events.collect { event ->
                when (event) {
                    is KPeerTransportEvent.DataChannelAvailable -> if (event.label == label) {
                        attachBufferedAmountIfPossible()
                    }
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

    public fun send(bytes: ByteArray): Boolean = connection.sendRaw(label, bytes)

    public fun send(text: String): Boolean = connection.sendTextRaw(label, text)

    /** Registers a callback for binary messages received on this channel. */
    public fun onBytes(handler: (ByteArray) -> Unit): KSubscription {
        val job = context.scope.launch {
            bytes.collect(handler)
        }
        return KSubscription { job.cancel() }
    }

    /** Registers a callback for text messages received on this channel. */
    public fun onText(handler: (String) -> Unit): KSubscription {
        val job = context.scope.launch {
            text.collect(handler)
        }
        return KSubscription { job.cancel() }
    }

    /** Registers a callback for channel state changes. */
    public fun onState(handler: (KChannelState) -> Unit): KSubscription {
        val job = context.scope.launch {
            state.collect(handler)
        }
        return KSubscription { job.cancel() }
    }

    public fun close() {
        _state.value = KChannelState.CLOSING
        connection.closeDataChannel(label)
    }
}
