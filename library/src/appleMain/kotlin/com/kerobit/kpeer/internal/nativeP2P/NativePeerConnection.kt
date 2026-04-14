@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.kerobit.kpeer.internal.nativeP2P

import com.kerobit.kpeer.KChannelConfig
import com.kerobit.kpeer.KPeerConnectionState
import com.kerobit.kpeer.KPeerContext
import com.kerobit.kpeer.KPeerIceCandidate
import com.kerobit.kpeer.KPeerSignal
import com.kerobit.kpeer.KPeerSdpType
import com.kerobit.kpeer.KPeerStat
import com.kerobit.kpeer.KPeerStatValue
import com.kerobit.kpeer.KPeerStatsReport
import com.kerobit.kpeer.internal.TransportConfig
import cocoapods.WebRTC_SDK.*
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.channels.Channel
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

private object PeerConnectionHolder {
    var peerConnectionFactory: RTCPeerConnectionFactory? = null

    fun getOrInit(): RTCPeerConnectionFactory {
        if (peerConnectionFactory != null) return peerConnectionFactory!!
        val encoderFactory = RTCDefaultVideoEncoderFactory()
        val decoderFactory = RTCDefaultVideoDecoderFactory()
        peerConnectionFactory = RTCPeerConnectionFactory(
            encoderFactory = encoderFactory,
            decoderFactory = decoderFactory
        )
        return peerConnectionFactory!!
    }
}

internal actual class NativePeerConnection actual constructor(
    config: TransportConfig,
    context: KPeerContext
) {
    private val delegateImpl = PeerConnectionDelegate(this)

    private val peerConnection: RTCPeerConnection = run {
        val factory = PeerConnectionHolder.getOrInit()
        val iceServers = config.iceServers.map { server ->
            if (server.username != null && server.credential != null) {
                RTCIceServer(
                    uRLStrings = listOf(server.url),
                    username = server.username,
                    credential = server.credential
                )
            } else {
                RTCIceServer(uRLStrings = listOf(server.url))
            }
        }
        val rtcConfig = RTCConfiguration().apply {
            this.iceServers = iceServers
            this.sdpSemantics = RTCSdpSemantics.RTCSdpSemanticsUnifiedPlan
            this.continualGatheringPolicy = RTCContinualGatheringPolicy.RTCContinualGatheringPolicyGatherContinually
        }
        val constraints = RTCMediaConstraints(
            mandatoryConstraints = null,
            optionalConstraints = null
        )
        factory.peerConnectionWithConfiguration(
            configuration = rtcConfig,
            constraints = constraints,
            delegate = delegateImpl
        ) ?: throw IllegalStateException("Failed to create RTCPeerConnection")
    }

    private val localIceCandidatesChannel = Channel<KPeerIceCandidate>(Channel.UNLIMITED)
    actual val localIceCandidates: Flow<KPeerIceCandidate> = localIceCandidatesChannel.receiveAsFlow()

    private val _connectionState = MutableStateFlow(KPeerConnectionState.CONNECTING)
    actual val connectionState: Flow<KPeerConnectionState> = _connectionState.asStateFlow()

    actual val currentConnectionState: KPeerConnectionState
        get() = _connectionState.value

    private val _incomingDataChannels = MutableSharedFlow<NativeDataChannel>(extraBufferCapacity = 8)
    actual val incomingDataChannels: Flow<NativeDataChannel> = _incomingDataChannels.asSharedFlow()
    private val _negotiationNeeded = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    actual val negotiationNeeded: Flow<Unit> = _negotiationNeeded.asSharedFlow()

    private val iceCandidateBuffer = IceCandidateBuffer<RTCIceCandidate>()

    actual suspend fun createOffer(): String = suspendCoroutine { cont ->
        val constraints = RTCMediaConstraints(
            mandatoryConstraints = null,
            optionalConstraints = null
        )
        peerConnection.offerForConstraints(constraints) { sdp, error ->
            if (error != null) {
                cont.resumeWithException(Exception("Failed to create offer: ${error.localizedDescription}"))
                return@offerForConstraints
            }
            if (sdp == null) {
                cont.resumeWithException(Exception("Offer SDP is null"))
                return@offerForConstraints
            }
            peerConnection.setLocalDescription(sdp) { setError ->
                if (setError != null) {
                    cont.resumeWithException(Exception("Failed to set local description: ${setError.localizedDescription}"))
                } else {
                    cont.resume(sdp.sdp)
                }
            }
        }
    }

    actual suspend fun createAnswer(): String = suspendCoroutine { cont ->
        val constraints = RTCMediaConstraints(
            mandatoryConstraints = null,
            optionalConstraints = null
        )
        peerConnection.answerForConstraints(constraints) { sdp, error ->
            if (error != null) {
                cont.resumeWithException(Exception("Failed to create answer: ${error.localizedDescription}"))
                return@answerForConstraints
            }
            if (sdp == null) {
                cont.resumeWithException(Exception("Answer SDP is null"))
                return@answerForConstraints
            }
            peerConnection.setLocalDescription(sdp) { setError ->
                if (setError != null) {
                    cont.resumeWithException(Exception("Failed to set local description: ${setError.localizedDescription}"))
                } else {
                    cont.resume(sdp.sdp)
                }
            }
        }
    }

    actual suspend fun setRemoteDescription(type: KPeerSdpType, sdp: String): Unit = suspendCoroutine { cont ->
        // During this window, incoming ICE candidates must be buffered until
        // the remote description is successfully applied.
        iceCandidateBuffer.reset()
        val rtcType = when (type) {
            KPeerSdpType.OFFER -> RTCSdpType.RTCSdpTypeOffer
            KPeerSdpType.ANSWER -> RTCSdpType.RTCSdpTypeAnswer
        }
        val sessionDescription = RTCSessionDescription(type = rtcType, sdp = sdp)
        peerConnection.setRemoteDescription(sessionDescription) { error ->
            if (error != null) {
                cont.resumeWithException(Exception("Failed to set remote description: ${error.localizedDescription}"))
            } else {
                iceCandidateBuffer.markRemoteDescriptionSetAndFlush { candidate ->
                    peerConnection.addIceCandidate(candidate, completionHandler = {})
                }
                cont.resume(Unit)
            }
        }
    }

    actual fun addIceCandidate(candidate: KPeerIceCandidate) {
        val iceCandidate = RTCIceCandidate(
            sdp = candidate.candidate,
            sdpMLineIndex = candidate.sdpMLineIndex ?: 0,
            sdpMid = candidate.sdpMid
        )
        iceCandidateBuffer.queueOrAdd(iceCandidate) { nativeCandidate ->
            peerConnection.addIceCandidate(nativeCandidate, completionHandler = {})
        }
    }

    actual suspend fun getStats(): KPeerStatsReport = suspendCoroutine { cont ->
        try {
            peerConnection.statisticsWithCompletionHandler { report ->
                if (report == null) {
                    cont.resume(KPeerStatsReport(stats = emptyList()))
                    return@statisticsWithCompletionHandler
                }
                cont.resume(report.toTyped())
            }
        } catch (e: Exception) {
            cont.resumeWithException(e)
        }
    }

    actual fun close() {
        peerConnection.close()
        localIceCandidatesChannel.close()
        _connectionState.value = KPeerConnectionState.DISCONNECTED
    }

    internal fun onIceCandidate(candidate: RTCIceCandidate) {
        val signalCandidate = KPeerIceCandidate(
            sdpMid = candidate.sdpMid,
            sdpMLineIndex = candidate.sdpMLineIndex,
            candidate = candidate.sdp
        )
        localIceCandidatesChannel.trySend(signalCandidate)
    }

    internal fun onIceGatheringComplete() {}

    internal fun onConnectionStateChange(state: RTCIceConnectionState) {
        val kpeerState = when (state) {
            RTCIceConnectionState.RTCIceConnectionStateNew,
            RTCIceConnectionState.RTCIceConnectionStateChecking -> KPeerConnectionState.CONNECTING
            RTCIceConnectionState.RTCIceConnectionStateConnected,
            RTCIceConnectionState.RTCIceConnectionStateCompleted -> KPeerConnectionState.CONNECTED
            RTCIceConnectionState.RTCIceConnectionStateDisconnected -> KPeerConnectionState.DISCONNECTED
            RTCIceConnectionState.RTCIceConnectionStateFailed -> KPeerConnectionState.FAILED
            RTCIceConnectionState.RTCIceConnectionStateClosed -> KPeerConnectionState.DISCONNECTED
            else -> KPeerConnectionState.DISCONNECTED
        }
        _connectionState.value = kpeerState
    }

    internal fun onDataChannel(channel: RTCDataChannel) {
        val native = NativeDataChannel(channel)
        _incomingDataChannels.tryEmit(native)
    }

    internal fun onNegotiationNeeded() {
        _negotiationNeeded.tryEmit(Unit)
    }

    actual fun createDataChannel(config: KChannelConfig): NativeDataChannel? {
        val controlParams = config.toControlParams()
        val controlConfig = RTCDataChannelConfiguration().apply {
            isOrdered = controlParams.ordered
            controlParams.maxRetransmitsOrNull?.let { maxRetransmits = it }
        }
        return peerConnection.dataChannelForLabel(config.label, configuration = controlConfig)?.let { dc ->
            // Apple platforms: bufferedAmountLowThreshold support varies by WebRTC build.
            NativeDataChannel(dc)
        }
    }
}

private fun RTCStatisticsReport.toTyped(): KPeerStatsReport {
    val entries = statistics.values.mapNotNull { it as? RTCStatistics }
    val stats = entries.map { stat ->
        val rawValues = stat.values
        val values = rawValues.entries.mapNotNull { (kAny, v) ->
            val k = kAny?.toString() ?: return@mapNotNull null
            k to toStatValue(v)
        }.toMap()

        KPeerStat(
            id = stat.id,
            type = stat.type,
            timestampUs = stat.timestamp_us.toLong(),
            values = values
        )
    }
    return KPeerStatsReport(stats = stats)
}

private class PeerConnectionDelegate(
    private val owner: NativePeerConnection
) : NSObject(), RTCPeerConnectionDelegateProtocol {

    override fun peerConnection(
        peerConnection: RTCPeerConnection,
        didGenerateIceCandidate: RTCIceCandidate
    ) {
        owner.onIceCandidate(didGenerateIceCandidate)
    }

    override fun peerConnection(
        peerConnection: RTCPeerConnection,
        didChangeIceConnectionState: RTCIceConnectionState
    ) {
        owner.onConnectionStateChange(didChangeIceConnectionState)
    }

    override fun peerConnection(
        peerConnection: RTCPeerConnection,
        didChangeIceGatheringState: RTCIceGatheringState
    ) {
        if (didChangeIceGatheringState == RTCIceGatheringState.RTCIceGatheringStateComplete) {
            owner.onIceGatheringComplete()
        }
    }

    override fun peerConnection(
        peerConnection: RTCPeerConnection,
        didOpenDataChannel: RTCDataChannel
    ) {
        owner.onDataChannel(didOpenDataChannel)
    }

    override fun peerConnection(
        peerConnection: RTCPeerConnection,
        didChangeSignalingState: RTCSignalingState
    ) {}

    override fun peerConnectionShouldNegotiate(peerConnection: RTCPeerConnection) {
        owner.onNegotiationNeeded()
    }

    @ObjCSignatureOverride
    override fun peerConnection(peerConnection: RTCPeerConnection, didRemoveStream: RTCMediaStream) {}

    @ObjCSignatureOverride
    override fun peerConnection(peerConnection: RTCPeerConnection, didAddStream: RTCMediaStream) {}

    override fun peerConnection(
        peerConnection: RTCPeerConnection,
        didRemoveIceCandidates: List<*>
    ) {}
}
