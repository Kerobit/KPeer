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
    private val _incoming = MutableSharedFlow<ByteArray>(extraBufferCapacity = 64)
    actual val incoming: Flow<ByteArray> = _incoming.asSharedFlow()

    private val _state = MutableStateFlow(mapState(dataChannel.readyState))
    actual val state: Flow<DataChannelState> = _state.asStateFlow()

    actual val currentState: DataChannelState
        get() = _state.value

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
            _incoming.tryEmit(bytes)
        }

        override fun dataChannel(
            dataChannel: RTCDataChannel,
            didChangeBufferedAmount: ULong
        ) {}
    }

    init {
        dataChannel.delegate = observer
    }

    actual fun send(data: ByteArray): Boolean {
        if (currentState != DataChannelState.OPEN) return false
        return try {
            val nsData = data.toNSData()
            val buffer = RTCDataBuffer(data = nsData, isBinary = true)
            dataChannel.sendData(buffer)
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
            dataChannel.sendData(buffer)
        } catch (e: Exception) {
            false
        }
    }

    actual fun close() {
        dataChannel.close()
    }

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
