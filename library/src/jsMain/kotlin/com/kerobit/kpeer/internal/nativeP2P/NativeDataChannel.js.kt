package com.kerobit.kpeer.internal.nativeP2P

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

internal actual class NativeDataChannel(
    private val channel: RTCDataChannel
) {
    private val core = NativeDataChannelCore(
        initialState = mapState(channel.readyState),
        initialBufferedAmount = channel.bufferedAmount.toLong(),
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
        get() = channel.label

    init {
        channel.onopen = {
            core.updateState(DataChannelState.OPEN)
        }
        channel.onclose = {
            core.updateState(DataChannelState.CLOSED)
        }
        channel.onclosing = {
            core.updateState(DataChannelState.CLOSING)
        }
        channel.onmessage = { event ->
            val data = event.data
            when {
                data != null && js("data instanceof ArrayBuffer") -> {
                    core.emitIncomingBytes(js("new Int8Array(data)").unsafeCast<ByteArray>())
                }
                js("typeof data === 'string'") -> core.emitIncomingText(data as String)
                else -> Unit
            }
        }
        channel.onbufferedamountlow = {
            core.updateBufferedAmount(channel.bufferedAmount.toLong())
        }
        if (channel.readyState == "open") {
            core.updateState(DataChannelState.OPEN)
        }
        core.updateBufferedAmount(channel.bufferedAmount.toLong())
    }

    actual fun send(data: ByteArray): Boolean {
        return core.trySendBytes(
            sendNative = {
                channel.send(data.unsafeCast<Any>())
                true
            },
            refreshBufferedAmount = { channel.bufferedAmount.toLong() }
        )
    }

    actual fun sendText(text: String): Boolean {
        return core.trySendText(
            sendNative = {
                channel.send(text)
                true
            },
            refreshBufferedAmount = { channel.bufferedAmount.toLong() }
        )
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
