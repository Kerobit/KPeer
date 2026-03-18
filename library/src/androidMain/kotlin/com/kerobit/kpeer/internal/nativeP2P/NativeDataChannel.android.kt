package com.kerobit.kpeer.internal.nativeP2P

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.DataChannel
import java.nio.ByteBuffer

internal actual class NativeDataChannel(
    private val dataChannel: DataChannel
) {
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    actual val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow(mapState(dataChannel.state()))
    actual val state: Flow<DataChannelState> = _state.asStateFlow()

    actual val currentState: DataChannelState
        get() = _state.value

    actual val label: String
        get() = dataChannel.label()

    init {
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {}
            override fun onStateChange() {
                _state.value = mapState(dataChannel.state())
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer?.let {
                    val data = ByteArray(it.data.remaining())
                    it.data.get(data)
                    _incoming.tryEmit(data)
                }
            }
        })
    }

    actual fun send(data: ByteArray): Boolean {
        if (currentState != DataChannelState.OPEN) return false
        return try {
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), true)
            dataChannel.send(buffer)
        } catch (e: Exception) {
            false
        }
    }

    actual fun sendText(text: String): Boolean {
        if (currentState != DataChannelState.OPEN) return false
        return try {
            val data = text.toByteArray(Charsets.UTF_8)
            val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), false)
            dataChannel.send(buffer)
        } catch (e: Exception) {
            false
        }
    }

    actual fun close() {
        dataChannel.close()
    }

    private fun mapState(state: DataChannel.State): DataChannelState = when (state) {
        DataChannel.State.CONNECTING -> DataChannelState.CONNECTING
        DataChannel.State.OPEN -> DataChannelState.OPEN
        DataChannel.State.CLOSING -> DataChannelState.CLOSING
        DataChannel.State.CLOSED -> DataChannelState.CLOSED
    }
}
