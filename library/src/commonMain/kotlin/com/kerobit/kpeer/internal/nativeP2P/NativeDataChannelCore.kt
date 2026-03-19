package com.kerobit.kpeer.internal.nativeP2P

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Core shared logic for a native WebRTC data channel:
 * - incoming message projections (bytes/text)
 * - state/bufferedAmount tracking
 * - send gating (only when OPEN)
 *
 * Platform-specific code is responsible for:
 * - decoding native payloads into bytes/text
 * - invoking the native send methods
 * - wiring state/bufferedAmount updates coming from the native layer
 */
internal class NativeDataChannelCore(
    initialState: DataChannelState,
    initialBufferedAmount: Long,
) {
    private val _incomingBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    val incomingBytes: Flow<ByteArray> = _incomingBytes.asSharedFlow()

    private val _incomingText = MutableSharedFlow<String>(extraBufferCapacity = 64)
    val incomingText: Flow<String> = _incomingText.asSharedFlow()

    private val _state = MutableStateFlow(initialState)
    val state: Flow<DataChannelState> = _state.asStateFlow()

    val currentState: DataChannelState
        get() = _state.value

    private val _bufferedAmount = MutableStateFlow(initialBufferedAmount)
    val bufferedAmount: Flow<Long> = _bufferedAmount.asStateFlow()

    val currentBufferedAmount: Long
        get() = _bufferedAmount.value

    fun emitIncomingBytes(data: ByteArray) {
        _incomingBytes.tryEmit(data)
    }

    fun emitIncomingText(text: String) {
        _incomingText.tryEmit(text)
    }

    fun updateState(state: DataChannelState) {
        _state.value = state
    }

    fun updateBufferedAmount(amount: Long) {
        _bufferedAmount.value = amount
    }

    fun trySendBytes(
        sendNative: () -> Boolean,
        refreshBufferedAmount: () -> Long,
    ): Boolean {
        if (currentState != DataChannelState.OPEN) return false
        return try {
            val ok = sendNative()
            updateBufferedAmount(refreshBufferedAmount())
            ok
        } catch (_: Throwable) {
            false
        }
    }

    fun trySendText(
        sendNative: () -> Boolean,
        refreshBufferedAmount: () -> Long,
    ): Boolean {
        if (currentState != DataChannelState.OPEN) return false
        return try {
            val ok = sendNative()
            updateBufferedAmount(refreshBufferedAmount())
            ok
        } catch (_: Throwable) {
            false
        }
    }
}

