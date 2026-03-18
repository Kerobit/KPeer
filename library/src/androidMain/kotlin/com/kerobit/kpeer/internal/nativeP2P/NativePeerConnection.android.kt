package com.kerobit.kpeer.internal.nativeP2P

import android.content.Context
import com.kerobit.kpeer.KPeerConnectionState
import com.kerobit.kpeer.KPeerContext
import com.kerobit.kpeer.internal.TransportConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.webrtc.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private object PeerConnectionHolder {
    var peerConnectionFactory: PeerConnectionFactory? = null

    fun getOrInit(context: Context): PeerConnectionFactory {
        if (peerConnectionFactory != null) return peerConnectionFactory!!
        val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(initOptions)
        val options = PeerConnectionFactory.Options()
        peerConnectionFactory = PeerConnectionFactory.builder()
            .setOptions(options)
            .createPeerConnectionFactory()
        return peerConnectionFactory!!
    }
}

internal actual class NativePeerConnection actual constructor(
    config: TransportConfig,
    context: KPeerContext
) {
    private val peerConnection: PeerConnection = run {
        val androidContext = context.platformContext as? Context
            ?: throw IllegalArgumentException("Android Context required for NativePeerConnection")
        val factory = PeerConnectionHolder.getOrInit(androidContext)
        val iceServers = config.iceServers.map { server ->
            val builder = PeerConnection.IceServer.builder(server.url)
            server.username?.let { builder.setUsername(it) }
            server.credential?.let { builder.setPassword(it) }
            builder.createIceServer()
        }
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        var self: NativePeerConnection? = null
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate?) {
                candidate?.let { self?.onIceCandidate(it) }
            }
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
            override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {
                state?.let { self?.onConnectionStateChange(it) }
            }
            override fun onIceConnectionReceivingChange(receiving: Boolean) {}
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
                if (state == PeerConnection.IceGatheringState.COMPLETE) self?.onIceGatheringComplete()
            }
            override fun onAddStream(stream: MediaStream?) {}
            override fun onRemoveStream(stream: MediaStream?) {}
            override fun onDataChannel(channel: DataChannel?) {
                channel?.let { self?.onDataChannel(it) }
            }
            override fun onRenegotiationNeeded() {
                self?.onNegotiationNeeded()
            }
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        }
        val pc = factory.createPeerConnection(rtcConfig, observer)
            ?: throw IllegalStateException("Failed to create PeerConnection")
        self = this@NativePeerConnection
        pc
    }

    private val _localIceCandidates = MutableSharedFlow<NativeIceCandidate>(extraBufferCapacity = 64)
    actual val localIceCandidates: Flow<NativeIceCandidate> = _localIceCandidates.asSharedFlow()

    private val _connectionState = MutableStateFlow(KPeerConnectionState.CONNECTING)
    actual val connectionState: Flow<KPeerConnectionState> = _connectionState.asStateFlow()

    actual val currentConnectionState: KPeerConnectionState
        get() = _connectionState.value

    private val _incomingDataChannels = MutableSharedFlow<NativeDataChannel>(extraBufferCapacity = 8)
    actual val incomingDataChannels: Flow<NativeDataChannel> = _incomingDataChannels.asSharedFlow()
    private val _negotiationNeeded = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    actual val negotiationNeeded: Flow<Unit> = _negotiationNeeded.asSharedFlow()

    private val pendingIceCandidates = mutableListOf<IceCandidate>()
    private var hasRemoteDescription = false

    actual suspend fun createOffer(): NativeSdp = suspendCoroutine { cont ->
        val constraints = MediaConstraints()
        peerConnection.createOffer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onSetSuccess() {
                            cont.resume(NativeSdp(SdpType.OFFER, it.description))
                        }
                        override fun onCreateFailure(error: String?) {}
                        override fun onSetFailure(error: String?) {
                            cont.resumeWithException(Exception("Failed to set local description: $error"))
                        }
                    }, it)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                cont.resumeWithException(Exception("Failed to create offer: $error"))
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    actual suspend fun createAnswer(): NativeSdp = suspendCoroutine { cont ->
        val constraints = MediaConstraints()
        peerConnection.createAnswer(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {
                sdp?.let {
                    peerConnection.setLocalDescription(object : SdpObserver {
                        override fun onCreateSuccess(sdp: SessionDescription?) {}
                        override fun onSetSuccess() {
                            cont.resume(NativeSdp(SdpType.ANSWER, it.description))
                        }
                        override fun onCreateFailure(error: String?) {}
                        override fun onSetFailure(error: String?) {
                            cont.resumeWithException(Exception("Failed to set local description: $error"))
                        }
                    }, it)
                }
            }
            override fun onSetSuccess() {}
            override fun onCreateFailure(error: String?) {
                cont.resumeWithException(Exception("Failed to create answer: $error"))
            }
            override fun onSetFailure(error: String?) {}
        }, constraints)
    }

    actual suspend fun setRemoteDescription(sdp: NativeSdp): Unit = suspendCoroutine { cont ->
        val type = when (sdp.type) {
            SdpType.OFFER -> SessionDescription.Type.OFFER
            SdpType.ANSWER -> SessionDescription.Type.ANSWER
        }
        val sessionDescription = SessionDescription(type, sdp.description)
        peerConnection.setRemoteDescription(object : SdpObserver {
            override fun onCreateSuccess(sdp: SessionDescription?) {}
            override fun onSetSuccess() {
                hasRemoteDescription = true
                pendingIceCandidates.forEach { candidate ->
                    peerConnection.addIceCandidate(candidate)
                }
                pendingIceCandidates.clear()
                cont.resume(Unit)
            }
            override fun onCreateFailure(error: String?) {}
            override fun onSetFailure(error: String?) {
                cont.resumeWithException(Exception("Failed to set remote description: $error"))
            }
        }, sessionDescription)
    }

    actual fun addIceCandidate(candidate: NativeIceCandidate) {
        val iceCandidate = IceCandidate(
            candidate.sdpMid,
            candidate.sdpMLineIndex,
            candidate.candidate
        )
        if (hasRemoteDescription) {
            peerConnection.addIceCandidate(iceCandidate)
        } else {
            pendingIceCandidates.add(iceCandidate)
        }
    }

    actual fun close() {
        peerConnection.close()
        _connectionState.value = KPeerConnectionState.DISCONNECTED
    }

    internal fun onIceCandidate(candidate: IceCandidate) {
        val nativeCandidate = NativeIceCandidate(
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex,
            candidate = candidate.sdp
        )
        _localIceCandidates.tryEmit(nativeCandidate)
    }

    internal fun onIceGatheringComplete() {}

    internal fun onConnectionStateChange(state: PeerConnection.IceConnectionState) {
        val kpeerState = when (state) {
            PeerConnection.IceConnectionState.NEW,
            PeerConnection.IceConnectionState.CHECKING -> KPeerConnectionState.CONNECTING
            PeerConnection.IceConnectionState.CONNECTED,
            PeerConnection.IceConnectionState.COMPLETED -> KPeerConnectionState.CONNECTED
            PeerConnection.IceConnectionState.DISCONNECTED -> KPeerConnectionState.DISCONNECTED
            PeerConnection.IceConnectionState.FAILED -> KPeerConnectionState.FAILED
            PeerConnection.IceConnectionState.CLOSED -> KPeerConnectionState.DISCONNECTED
        }
        _connectionState.value = kpeerState
    }

    internal fun onDataChannel(channel: DataChannel) {
        val native = NativeDataChannel(channel)
        _incomingDataChannels.tryEmit(native)
    }

    internal fun onNegotiationNeeded() {
        _negotiationNeeded.tryEmit(Unit)
    }

    actual fun createDataChannel(label: String, ordered: Boolean, reliable: Boolean): NativeDataChannel? {
        val controlConfig = DataChannel.Init().apply {
            this.ordered = ordered
            if (!reliable) {
                maxRetransmits = 0
            }
        }
        return peerConnection.createDataChannel(label, controlConfig)?.let { NativeDataChannel(it) }
    }
}
