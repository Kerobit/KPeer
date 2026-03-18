package com.kerobit.kpeer.internal.nativeP2P

import kotlinx.coroutines.flow.Flow

internal expect class NativeDataChannel {
    val incoming: Flow<ByteArray>
    val state: Flow<DataChannelState>
    val currentState: DataChannelState
    val label: String
    fun send(data: ByteArray): Boolean
    fun sendText(text: String): Boolean
    fun close()
}

enum class DataChannelState {
    CONNECTING,
    OPEN,
    CLOSING,
    CLOSED
}
