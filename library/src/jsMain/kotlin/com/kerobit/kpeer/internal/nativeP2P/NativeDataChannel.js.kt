package com.kerobit.kpeer.internal.nativeP2P

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual class NativeDataChannel(
    private val channel: RTCDataChannel
) {
    private val _incomingBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    actual val incomingBytes: Flow<ByteArray> = _incomingBytes.asSharedFlow()
    private val _incomingText = MutableSharedFlow<String>(extraBufferCapacity = 64)
    actual val incomingText: Flow<String> = _incomingText.asSharedFlow()

    private val _state = MutableStateFlow(mapState(channel.readyState))
    actual val state: Flow<DataChannelState> = _state.asStateFlow()

    actual val currentState: DataChannelState
        get() = _state.value

    actual val label: String
        get() = channel.label

    init {
        channel.onopen = {
            _state.value = DataChannelState.OPEN
        }
        channel.onclose = {
            _state.value = DataChannelState.CLOSED
        }
        channel.onclosing = {
            _state.value = DataChannelState.CLOSING
        }
        channel.onmessage = { event ->
            val data = event.asDynamic().data
            when {
                data != null && js("data instanceof ArrayBuffer") -> {
                    _incomingBytes.tryEmit(js("new Int8Array(data)").unsafeCast<ByteArray>())
                }
                js("typeof data === 'string'") -> _incomingText.tryEmit(data as String)
                else -> Unit
            }
        }
        if (channel.readyState == "open") {
            _state.value = DataChannelState.OPEN
        }
    }

    actual fun send(data: ByteArray): Boolean {
        if (currentState != DataChannelState.OPEN) return false
        return try {
            channel.send(data.unsafeCast<Any>())
        } catch (e: Throwable) {
            false
        }
    }

    actual fun sendText(text: String): Boolean {
        if (currentState != DataChannelState.OPEN) return false
        return try {
            channel.send(text)
        } catch (e: Throwable) {
            false
        }
    }

    actual fun close() {
        channel.close()
    }

    private fun mapState(readyState: String): DataChannelState = when (readyState) {
        "connecting" -> DataChannelState.CONNECTING
        "open" -> DataChannelState.OPEN
        "closing" -> DataChannelState.CLOSING
        "closed" -> DataChannelState.CLOSED
        else -> DataChannelState.CLOSED
    }
}
