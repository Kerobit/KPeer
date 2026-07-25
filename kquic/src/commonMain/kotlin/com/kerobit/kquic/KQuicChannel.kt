package com.kerobit.kquic

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineStart

enum class KQuicDelivery { RELIABLE_ORDERED, UNRELIABLE }

data class KQuicChannelConfig(
    val label: String,
    val delivery: KQuicDelivery = KQuicDelivery.RELIABLE_ORDERED
) {
    init {
        require(label.isNotBlank())
    }
}

/** A labeled application channel backed by one QUIC stream or QUIC DATAGRAM frames. */
class KQuicChannel internal constructor(
    private val context: KQuicContext,
    val label: String,
    val delivery: KQuicDelivery,
    private val send: (KQuicChannel, ByteArray, Boolean) -> Boolean,
    private val closeChannel: (KQuicChannel) -> Unit
) {
    private val _state = MutableStateFlow(KQuicChannelState.CONNECTING)
    private val _bytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    private val _text = MutableSharedFlow<String>(extraBufferCapacity = 64)

    val state: Flow<KQuicChannelState> = _state.asStateFlow()
    val bytes: Flow<ByteArray> = _bytes.asSharedFlow()
    val text: Flow<String> = _text.asSharedFlow()
    val currentState: KQuicChannelState get() = _state.value

    fun send(bytes: ByteArray): Boolean =
        _state.value == KQuicChannelState.OPEN && send(this, bytes, false)

    fun send(text: String): Boolean =
        _state.value == KQuicChannelState.OPEN && send(this, text.encodeToByteArray(), true)

    fun onBytes(handler: (ByteArray) -> Unit): KQuicSubscription {
        val job = context.scope.launch(start = CoroutineStart.UNDISPATCHED) { bytes.collect(handler) }
        return KQuicSubscription { job.cancel() }
    }

    fun onText(handler: (String) -> Unit): KQuicSubscription {
        val job = context.scope.launch(start = CoroutineStart.UNDISPATCHED) { text.collect(handler) }
        return KQuicSubscription { job.cancel() }
    }

    fun onState(handler: (KQuicChannelState) -> Unit): KQuicSubscription {
        val job = context.scope.launch(start = CoroutineStart.UNDISPATCHED) { state.collect(handler) }
        return KQuicSubscription { job.cancel() }
    }

    fun close() {
        if (_state.value == KQuicChannelState.CLOSED) return
        _state.value = KQuicChannelState.CLOSING
        closeChannel(this)
    }

    internal fun markOpen() { _state.value = KQuicChannelState.OPEN }
    internal fun markClosed() { _state.value = KQuicChannelState.CLOSED }
    internal fun emitBytes(value: ByteArray) { _bytes.tryEmit(value) }
    internal fun emitText(value: String) { _text.tryEmit(value) }
}
