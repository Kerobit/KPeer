package com.kerobit.kpeer.internal.nativeP2P

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

internal actual class NativeDataChannel {
    actual val incoming: Flow<ByteArray> = emptyFlow()
    actual val state: Flow<DataChannelState> = emptyFlow()
    actual val currentState: DataChannelState = DataChannelState.CLOSED
    actual val label: String = ""

    actual fun send(data: ByteArray): Boolean {
        throw UnsupportedOperationException("P2P not supported on JVM")
    }

    actual fun sendText(text: String): Boolean {
        throw UnsupportedOperationException("P2P not supported on JVM")
    }

    actual fun close() {}
}
