@file:OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.kerobit.kpeer.internal.nativeP2P

import cocoapods.WebRTC_SDK.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSData
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.memcpy

internal actual class NativeDataChannel(
    private val dataChannel: RTCDataChannel
) {
    private val _incomingBytes = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    actual val incomingBytes: Flow<ByteArray> = _incomingBytes.asSharedFlow()
    private val _incomingText = MutableSharedFlow<String>(extraBufferCapacity = 64)
    actual val incomingText: Flow<String> = _incomingText.asSharedFlow()

    private val _state = MutableStateFlow(mapState(dataChannel.readyState))
    actual val state: Flow<DataChannelState> = _state.asStateFlow()

    actual val currentState: DataChannelState
        get() = _state.value

    private val _bufferedAmount = MutableStateFlow(dataChannel.bufferedAmount.toLong())
    actual val bufferedAmount: Flow<Long> = _bufferedAmount.asStateFlow()

    actual val currentBufferedAmount: Long
        get() = _bufferedAmount.value

    actual val label: String
        get() = dataChannel.label

    private val observer = object : NSObject(), RTCDataChannelDelegateProtocol {
        override fun dataChannelDidChangeState(dataChannel: RTCDataChannel) {
            _state.value = mapState(dataChannel.readyState)
        }

        override fun dataChannel(
            dataChannel: RTCDataChannel,
            didReceiveMessageWithBuffer: RTCDataBuffer
        ) {
            val bytes = didReceiveMessageWithBuffer.data.toByteArray()
            if (didReceiveMessageWithBuffer.isBinary) {
                _incomingBytes.tryEmit(bytes)
            } else {
                _incomingText.tryEmit(bytes.decodeToString())
            }
        }

        override fun dataChannel(
            dataChannel: RTCDataChannel,
            didChangeBufferedAmount: ULong
        ) {
            _bufferedAmount.value = didChangeBufferedAmount.toLong()
        }
    }

    init {
        dataChannel.delegate = observer
        _bufferedAmount.value = dataChannel.bufferedAmount.toLong()
    }

    actual fun send(data: ByteArray): Boolean {
        if (currentState != DataChannelState.OPEN) return false
        return try {
            val nsData = data.toNSData()
            val buffer = RTCDataBuffer(data = nsData, isBinary = true)
            val ok = dataChannel.sendData(buffer)
            _bufferedAmount.value = dataChannel.bufferedAmount.toLong()
            ok
        } catch (e: Exception) {
            false
        }
    }

    actual fun sendText(text: String): Boolean {
        if (currentState != DataChannelState.OPEN) return false
        return try {
            val data = text.encodeToByteArray()
            val nsData = data.toNSData()
            val buffer = RTCDataBuffer(data = nsData, isBinary = false)
            val ok = dataChannel.sendData(buffer)
            _bufferedAmount.value = dataChannel.bufferedAmount.toLong()
            ok
        } catch (e: Exception) {
            false
        }
    }

    actual fun close() {
        dataChannel.close()
    }

    @Suppress("REDUNDANT_ELSE_IN_WHEN")
    private fun mapState(state: RTCDataChannelState): DataChannelState = when (state) {
        RTCDataChannelState.RTCDataChannelStateConnecting -> DataChannelState.CONNECTING
        RTCDataChannelState.RTCDataChannelStateOpen -> DataChannelState.OPEN
        RTCDataChannelState.RTCDataChannelStateClosing -> DataChannelState.CLOSING
        RTCDataChannelState.RTCDataChannelStateClosed -> DataChannelState.CLOSED
        else -> DataChannelState.CLOSED
    }
}

private fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return byteArrayOf()
    return ByteArray(size).apply {
        usePinned { pinned ->
            memcpy(pinned.addressOf(0), bytes, length)
        }
    }
}
