package com.kerobit.kpeer

import com.kerobit.kpeer.internal.KPeerConnection
import com.kerobit.kpeer.internal.nativeP2P.NativeIceCandidate
import com.kerobit.kpeer.internal.nativeP2P.NativeSdp
import com.kerobit.kpeer.internal.nativeP2P.SdpType
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

internal class KSignaler(
    private val context: KPeerContext,
    private val config: KPeerConfig,
    private val connection: KPeerConnection,
    private val logger: KPeerLogger,
    private val signalsSink: MutableSharedFlow<KPeerSignal>
) {
    private var signalingJob: Job? = null
    private var offerJob: Job? = null
    private var started = false
    private var pendingOffer = false

    init {
        context.scope.launch {
            connection.negotiationNeeded.collect {
                if (config.initiator) {
                    requestOffer()
                }
            }
        }
        if (config.initiator) {
            startAsInitiator()
        }
    }

    fun ensureStarted() {
        if (started) return
        started = true
        connection.startConnect()
        if (signalingJob == null) {
            signalingJob = context.scope.launch {
                connection.localIceCandidates.collect { candidate ->
                    signalsSink.tryEmit(candidate.toSignal())
                }
            }
        }
    }

    suspend fun handleSignal(remote: KPeerSignal) {
        when (remote) {
            is KPeerSignal.Offer -> {
                // The answering side lazily starts the transport when the first offer arrives.
                if (!config.initiator) {
                    ensureStarted()
                }
                connection.setRemoteDescription(NativeSdp(SdpType.OFFER, remote.sdp))
                if (!config.initiator) {
                    val answer = connection.createAnswer()
                    signalsSink.tryEmit(KPeerSignal.Answer(answer.description))
                }
            }
            is KPeerSignal.Answer -> {
                connection.setRemoteDescription(NativeSdp(SdpType.ANSWER, remote.sdp))
            }
            is KPeerSignal.IceCandidate -> {
                connection.addIceCandidate(
                    NativeIceCandidate(
                        sdpMid = remote.sdpMid,
                        sdpMLineIndex = remote.sdpMLineIndex ?: 0,
                        candidate = remote.candidate
                    )
                )
            }
        }
    }

    fun close() {
        signalingJob?.cancel()
        offerJob?.cancel()
    }

    private fun startAsInitiator() {
        ensureStarted()
        requestOffer()
    }

    private fun requestOffer() {
        if (!config.initiator) return
        ensureStarted()
        if (offerJob?.isActive == true) {
            // Multiple channel creations can trigger renegotiation back-to-back. Collapse them
            // into a single extra offer once the current createOffer() finishes.
            pendingOffer = true
            return
        }
        offerJob = context.scope.launch {
            do {
                pendingOffer = false
                try {
                    val offer = connection.createOffer()
                    signalsSink.tryEmit(KPeerSignal.Offer(offer.description))
                } catch (e: Exception) {
                    logger.warn("Failed to create offer: ${e.message}")
                }
            } while (pendingOffer)
        }
    }

    private fun NativeIceCandidate.toSignal(): KPeerSignal.IceCandidate {
        return KPeerSignal.IceCandidate(
            candidate = candidate,
            sdpMid = sdpMid,
            sdpMLineIndex = sdpMLineIndex
        )
    }
}
