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
    private val core = NativeDataChannelCore(
        initialState = mapState(dataChannel.state()),
        initialBufferedAmount = dataChannel.bufferedAmount()
    )

    actual val incomingBytes: Flow<ByteArray> = core.incomingBytes
    actual val incomingText: Flow<String> = core.incomingText
    actual val state: Flow<DataChannelState> = core.state

    actual val currentState: DataChannelState
        get() = core.currentState

    actual val bufferedAmount: Flow<Long> = core.bufferedAmount

    actual val currentBufferedAmount: Long
        get() = core.currentBufferedAmount

    actual val label: String
        get() = dataChannel.label()

    init {
        dataChannel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) {
                core.updateBufferedAmount(dataChannel.bufferedAmount())
            }
            override fun onStateChange() {
                core.updateState(mapState(dataChannel.state()))
            }
            override fun onMessage(buffer: DataChannel.Buffer?) {
                buffer?.let {
                    val data = ByteArray(it.data.remaining())
                    it.data.get(data)
                    if (it.binary) {
                        core.emitIncomingBytes(data)
                    } else {
                        core.emitIncomingText(data.decodeToString())
                    }
                }
            }
        })
    }

    actual fun send(data: ByteArray): Boolean {
        return core.trySendBytes(
            sendNative = {
                val buffer = DataChannel.Buffer(ByteBuffer.wrap(data), true)
                dataChannel.send(buffer)
            },
            refreshBufferedAmount = { dataChannel.bufferedAmount() }
        )
    }

    actual fun sendText(text: String): Boolean {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        return core.trySendText(
            sendNative = {
                val buffer = DataChannel.Buffer(ByteBuffer.wrap(textBytes), false)
                dataChannel.send(buffer)
            },
            refreshBufferedAmount = { dataChannel.bufferedAmount() }
        )
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
