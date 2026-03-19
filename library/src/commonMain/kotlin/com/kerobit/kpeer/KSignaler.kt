package com.kerobit.kpeer

import com.kerobit.kpeer.internal.KPeerConnection
import com.kerobit.kpeer.internal.nativeP2P.NativeIceCandidate
import com.kerobit.kpeer.internal.nativeP2P.NativeSdp
import com.kerobit.kpeer.internal.nativeP2P.SdpType
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    private val signalMutex = Mutex()

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
                val signalingCfg = config.signaling
                val flushIntervalMs = signalingCfg.flushInterval

                // No batching: emit every candidate as soon as it is generated.
                if (flushIntervalMs <= 0L) {
                    connection.localIceCandidates.collect { candidate ->
                        signalsSink.emit(candidate.toSignal())
                    }
                    return@launch
                }

                val pendingLocalCandidates = mutableListOf<NativeIceCandidate>()
                var flushTimerJob: Job? = null
                val batchMutex = Mutex()

                suspend fun flushBatch() {
                    val batch = batchMutex.withLock {
                        if (pendingLocalCandidates.isEmpty()) return@withLock emptyList()
                        val out = pendingLocalCandidates.toList()
                        pendingLocalCandidates.clear()
                        flushTimerJob = null
                        out
                    }
                    if (batch.isEmpty()) return
                    for (c in batch) {
                        // Use emit() to avoid dropping ICE candidates.
                        signalsSink.emit(c.toSignal())
                    }
                }

                connection.localIceCandidates.collect { candidate ->
                    val maxBatchSize = signalingCfg.maxBatchSize

                    var timerToCancel: Job? = null
                    var flushNow = false

                    batchMutex.withLock {
                        pendingLocalCandidates.add(candidate)

                        // flush-after-first semantics:
                        // start the timer when we see the first candidate of the current batch.
                        if (flushTimerJob == null) {
                            flushTimerJob = launch {
                                delay(flushIntervalMs)
                                flushBatch()
                            }
                        }

                        if (maxBatchSize != null && pendingLocalCandidates.size >= maxBatchSize) {
                            timerToCancel = flushTimerJob
                            flushNow = true
                        }
                    }

                    timerToCancel?.cancel()
                    if (flushNow) {
                        flushBatch()
                    }
                }
            }
        }
    }

    suspend fun handleSignal(remote: KPeerSignal) {
        signalMutex.withLock {
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
                    val nativeCandidate = NativeIceCandidate(
                        sdpMid = remote.sdpMid,
                        sdpMLineIndex = remote.sdpMLineIndex ?: 0,
                        candidate = remote.candidate
                    )
                    // Buffering (if any) is handled by the native transport layer.
                    connection.addIceCandidate(nativeCandidate)
                }
            }
        }
    }

    fun close() {
        signalingJob?.cancel()
        offerJob?.cancel()
    }

    private fun startAsInitiator() {
        ensureStarted()
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
