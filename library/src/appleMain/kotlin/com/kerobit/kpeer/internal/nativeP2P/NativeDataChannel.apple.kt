@file:OptIn(ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.kerobit.kpeer.internal.nativeP2P

import cocoapods.WebRTC_SDK.*
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.Flow
import platform.Foundation.NSData
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.memcpy

internal actual class NativeDataChannel(
    private val dataChannel: RTCDataChannel
) {
    private val core = NativeDataChannelCore(
        initialState = mapState(dataChannel.readyState),
        initialBufferedAmount = dataChannel.bufferedAmount.toLong(),
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
        get() = dataChannel.label

    private val observer = object : NSObject(), RTCDataChannelDelegateProtocol {
        override fun dataChannelDidChangeState(dataChannel: RTCDataChannel) {
            core.updateState(mapState(dataChannel.readyState))
        }

        override fun dataChannel(
            dataChannel: RTCDataChannel,
            didReceiveMessageWithBuffer: RTCDataBuffer
        ) {
            val bytes = didReceiveMessageWithBuffer.data.toByteArray()
            if (didReceiveMessageWithBuffer.isBinary) {
                core.emitIncomingBytes(bytes)
            } else {
                core.emitIncomingText(bytes.decodeToString())
            }
        }

        override fun dataChannel(
            dataChannel: RTCDataChannel,
            didChangeBufferedAmount: ULong
        ) {
            core.updateBufferedAmount(didChangeBufferedAmount.toLong())
        }
    }

    init {
        dataChannel.delegate = observer
        core.updateBufferedAmount(dataChannel.bufferedAmount.toLong())
    }

    actual fun send(data: ByteArray): Boolean {
        return core.trySendBytes(
            sendNative = {
                val nsData = data.toNSData()
                val buffer = RTCDataBuffer(data = nsData, isBinary = true)
                dataChannel.sendData(buffer)
            },
            refreshBufferedAmount = { dataChannel.bufferedAmount.toLong() }
        )
    }

    actual fun sendText(text: String): Boolean {
        return core.trySendText(
            sendNative = {
                val data = text.encodeToByteArray()
                val nsData = data.toNSData()
                val buffer = RTCDataBuffer(data = nsData, isBinary = false)
                dataChannel.sendData(buffer)
            },
            refreshBufferedAmount = { dataChannel.bufferedAmount.toLong() }
        )
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
