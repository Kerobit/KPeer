package com.kerobit.kpeer.internal

import com.kerobit.kpeer.KPeerConnectionState
import com.kerobit.kpeer.KPeerContext
import com.kerobit.kpeer.KPeerLogger
import com.kerobit.kpeer.internal.nativeP2P.DataChannelState
import com.kerobit.kpeer.internal.nativeP2P.NativeDataChannel
import com.kerobit.kpeer.internal.nativeP2P.NativeIceCandidate
import com.kerobit.kpeer.internal.nativeP2P.NativePeerConnection
import com.kerobit.kpeer.internal.nativeP2P.NativeSdp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

internal class KPeerConnection(
    context: KPeerContext,
    private val config: TransportConfig,
    private val logger: KPeerLogger
) {
    private val nativePeerConnection = NativePeerConnection(config, context)
    private val scopeJob = SupervisorJob(context.scope.coroutineContext[Job])
    private val scope = CoroutineScope(context.scope.coroutineContext + scopeJob)

    private val _events = MutableSharedFlow<KPeerTransportEvent>(extraBufferCapacity = 64)
    val events: Flow<KPeerTransportEvent> = _events.asSharedFlow()

    val localIceCandidates: Flow<NativeIceCandidate> = nativePeerConnection.localIceCandidates
    val connectionState: Flow<KPeerConnectionState> = nativePeerConnection.connectionState
    val currentConnectionState: KPeerConnectionState get() = nativePeerConnection.currentConnectionState
    val negotiationNeeded: Flow<Unit> = nativePeerConnection.negotiationNeeded

    private var started = false
    private val channelsByLabel = linkedMapOf<String, NativeDataChannel>()
    private val attachedLabels = mutableSetOf<String>()

    fun startConnect() {
        if (started) return
        started = true

        scope.launch {
            nativePeerConnection.connectionState.collect { state ->
                when (state) {
                    KPeerConnectionState.CONNECTED -> _events.emit(KPeerTransportEvent.Connected)
                    KPeerConnectionState.DISCONNECTED -> _events.emit(KPeerTransportEvent.Disconnected)
                    KPeerConnectionState.FAILED -> _events.emit(KPeerTransportEvent.Failed)
                    KPeerConnectionState.CONNECTING -> Unit
                }
            }
        }
        scope.launch {
            nativePeerConnection.incomingDataChannels.collect { channel ->
                registerAndAttach(channel)
            }
        }
    }

    private fun registerAndAttach(channel: NativeDataChannel) {
        channelsByLabel[channel.label] = channel
        if (!attachedLabels.add(channel.label)) return

        scope.launch {
            logger.info("Data channel available label=${channel.label}")
            _events.emit(KPeerTransportEvent.DataChannelAvailable(channel.label))
            channel.state.collect { state ->
                when (state) {
                    DataChannelState.OPEN -> {
                        logger.info("Data channel OPEN label=${channel.label}")
                        _events.emit(KPeerTransportEvent.DataChannelOpen(channel.label))
                    }
                    DataChannelState.CLOSED -> {
                        logger.info("Data channel CLOSED label=${channel.label}")
                        _events.emit(KPeerTransportEvent.DataChannelClosed(channel.label))
                    }
                    DataChannelState.CONNECTING, DataChannelState.CLOSING -> {
                        logger.debug("Data channel state=$state label=${channel.label}")
                    }
                }
            }
        }
        scope.launch {
            channel.incomingBytes.collect { data ->
                _events.emit(KPeerTransportEvent.BytesReceived(channel.label, data))
            }
        }
        scope.launch {
            channel.incomingText.collect { text ->
                _events.emit(KPeerTransportEvent.TextReceived(channel.label, text))
            }
        }
    }

    fun sendRaw(label: String, data: ByteArray): Boolean {
        val channel = channelsByLabel[label] ?: return false
        return channel.send(data)
    }

    fun sendTextRaw(label: String, text: String): Boolean {
        val channel = channelsByLabel[label] ?: return false
        return channel.sendText(text)
    }

    fun getDataChannel(label: String): NativeDataChannel? = channelsByLabel[label]

    suspend fun createDataChannel(
        label: String,
        ordered: Boolean,
        reliable: Boolean
    ): NativeDataChannel? {
        channelsByLabel[label]?.let { return it }
        val created = nativePeerConnection.createDataChannel(
            label = label,
            ordered = ordered,
            reliable = reliable
        )
        if (created != null) {
            registerAndAttach(created)
        }
        return created
    }

    fun closeDataChannel(label: String) {
        channelsByLabel[label]?.close()
    }

    suspend fun createOffer(): NativeSdp {
        return nativePeerConnection.createOffer()
    }

    suspend fun createAnswer(): NativeSdp = nativePeerConnection.createAnswer()
    suspend fun setRemoteDescription(sdp: NativeSdp) = nativePeerConnection.setRemoteDescription(sdp)
    fun addIceCandidate(candidate: NativeIceCandidate) = nativePeerConnection.addIceCandidate(candidate)

    fun close() {
        scopeJob.cancel()
        nativePeerConnection.close()
    }
}

internal sealed class KPeerTransportEvent {
    data object Connected : KPeerTransportEvent()
    data object Disconnected : KPeerTransportEvent()
    data object Failed : KPeerTransportEvent()
    data class DataChannelAvailable(val label: String) : KPeerTransportEvent()
    data class DataChannelOpen(val label: String) : KPeerTransportEvent()
    data class DataChannelClosed(val label: String) : KPeerTransportEvent()
    data class BytesReceived(val label: String, val data: ByteArray) : KPeerTransportEvent()
    data class TextReceived(val label: String, val text: String) : KPeerTransportEvent()
}
