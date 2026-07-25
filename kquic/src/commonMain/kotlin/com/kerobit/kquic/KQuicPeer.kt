package com.kerobit.kquic

import com.kerobit.kquic.internal.KQuicCandidateSelector
import com.kerobit.kquic.internal.extractSubjectPublicKeyInfo
import com.kerobit.kquic.internal.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * P2P QUIC facade. The app forwards [signals] to the remote peer and passes received
 * signals to [signal]. Candidate gathering and packet I/O remain behind platform drivers.
 */
@ExperimentalKQuicApi
class KQuicPeer(
    private val context: KQuicContext,
    private val config: KQuicConfig
) {
    private val _signals = MutableSharedFlow<KQuicSignal>(replay = 64, extraBufferCapacity = 64)
    private val _channels = MutableSharedFlow<KQuicChannel>(extraBufferCapacity = 16)
    private val _state = MutableStateFlow(KQuicPeerConnectionState.NEW)
    private val _stats = MutableStateFlow(KQuicStats())
    private val localCandidates = linkedSetOf<KQuicCandidate>()
    private val remoteCandidates = linkedSetOf<KQuicCandidate>()
    private val channelsByLabel = linkedMapOf<String, KQuicChannel>()
    private val emittedChannelLabels = mutableSetOf<String>()
    private val attemptedCandidatePairs = mutableSetOf<KQuicCandidatePair>()
    private val gatheringJobs = mutableListOf<Job>()
    private val stateMutex = Mutex()
    private val transport = context.transportFactory.create(config)
    private val transportEventsJob: Job
    private val identityReady = CompletableDeferred<KQuicIdentity>()
    private var connectionJob: Job? = null
    private var identityAndGatheringJob: Job? = null
    private var localIdentityPublicKeySha256: String? = null
    private var remoteIdentityPublicKeySha256: String? = null
    private var localSignalSent = false
    private var localGatheringComplete = false
    private var remoteGatheringComplete = false
    private var lastConnectionError: KQuicError? = null
    private var started = false
    private var closed = false

    val signals: Flow<KQuicSignal> = _signals.asSharedFlow()
    val channels: Flow<KQuicChannel> = _channels.asSharedFlow()
    val connectionState: Flow<KQuicPeerConnectionState> = _state.asStateFlow()
    val stats: Flow<KQuicStats> = _stats.asStateFlow()
    val currentConnectionState: KQuicPeerConnectionState get() = _state.value

    init {
        transportEventsJob = context.scope.launch {
            transport.events.collect { event ->
                stateMutex.withLock {
                    handleTransportEvent(event)
                }
            }
        }
    }

    /** Starts local candidate gathering and emits an offer from the signaling initiator. */
    fun start() {
        if (started || closed) return
        _state.value = KQuicPeerConnectionState.GATHERING
        localCandidates += config.initialCandidates
        started = true
        identityAndGatheringJob = context.scope.launch {
            try {
                val identity = when (val security = config.security) {
                    is KQuicSecurity.Explicit -> security.identityProvider.identityFor(
                        KQuicHandshakeRequest(config.rendezvousId, config.initiator)
                    )
                    KQuicSecurity.EphemeralAuthenticatedSignaling ->
                        context.platformServices.ephemeralIdentityProvider.identityFor(
                            KQuicHandshakeRequest(config.rendezvousId, config.initiator)
                        )
                }
                localIdentityPublicKeySha256 = identity.cachedPublicKeySha256
                    ?: context.platformServices.sha256(
                        extractSubjectPublicKeyInfo(identity.certificateChainDer.first())
                    ).toHex()
                identityReady.complete(identity)

                if (config.initiator) {
                    emitInitialSignal()
                }

                val sources = buildList {
                    if (config.gatherHostCandidates) {
                        context.platformServices.hostCandidateSource?.let(::add)
                    }
                    addAll(config.candidateSources)
                }
                coroutineScope {
                    sources.map { source ->
                        launch {
                            source.candidates().collect(::addLocalCandidate)
                        }.also(gatheringJobs::add)
                    }.joinAll()
                }
                stateMutex.withLock {
                    localGatheringComplete = true
                    if (localSignalSent) {
                        emitCandidateSnapshot()
                        _signals.emit(KQuicEndOfCandidates(config.rendezvousId))
                    }
                    selectCandidatePairLocked()
                    failIfCandidatePairsExhaustedLocked()
                }
            } catch (error: CancellationException) {
                if (!closed) throw error
            } catch (error: Throwable) {
                if (!identityReady.isCompleted) identityReady.completeExceptionally(error)
                fail(error.toKQuicError("Failed to initialize KQuic identity or candidates"))
            }
        }
    }

    /** Applies an app-delivered rendezvous message. It never performs application signaling itself. */
    suspend fun signal(remote: KQuicSignal) {
        if (closed) throw KQuicError.InvalidSignal("KQuicPeer is closed")
        if (remote.rendezvousId != config.rendezvousId) {
            throw KQuicError.InvalidSignal("Signal rendezvousId does not match this peer")
        }
        if (!started) start()
        stateMutex.withLock {
            when (remote) {
                is KQuicOffer -> {
                    if (config.initiator) throw KQuicError.InvalidSignal("Initiator received a KQuicOffer")
                    setRemoteIdentityFingerprint(remote.identityPublicKeySha256)
                    remote.candidates.forEach(::addRemoteCandidateLocked)
                    remoteGatheringComplete = remote.endOfCandidates
                    val identity = identityReady.await()
                    _signals.emit(
                        KQuicAnswer(
                            rendezvousId = config.rendezvousId,
                            candidates = localCandidates.toList(),
                            identityPublicKeySha256 = checkNotNull(localIdentityPublicKeySha256),
                            endOfCandidates = localGatheringComplete
                        )
                    )
                    localSignalSent = true
                    if (localGatheringComplete) {
                        _signals.emit(KQuicEndOfCandidates(config.rendezvousId))
                    }
                }
                is KQuicAnswer -> {
                    if (!config.initiator) throw KQuicError.InvalidSignal("Responder received a KQuicAnswer")
                    setRemoteIdentityFingerprint(remote.identityPublicKeySha256)
                    remote.candidates.forEach(::addRemoteCandidateLocked)
                    remoteGatheringComplete = remote.endOfCandidates
                }
                is KQuicCandidateUpdate -> {
                    remote.candidates.forEach(::addRemoteCandidateLocked)
                    if (remote.endOfCandidates) remoteGatheringComplete = true
                }
                is KQuicEndOfCandidates -> remoteGatheringComplete = true
            }
            selectCandidatePairLocked()
            failIfCandidatePairsExhaustedLocked()
        }
    }

    /** Creates a logical channel once the platform QUIC handshake confirms the connection. */
    fun createChannel(channelConfig: KQuicChannelConfig): KQuicChannel {
        ensureConnected()
        val labelBytes = channelConfig.label.encodeToByteArray().size
        if (labelBytes > config.limits.maxChannelLabelBytes) {
            throw KQuicError.InvalidChannel(
                "Channel label uses $labelBytes UTF-8 bytes; maximum is ${config.limits.maxChannelLabelBytes}"
            )
        }
        channelsByLabel[channelConfig.label]?.let { existing ->
            if (existing.delivery != channelConfig.delivery) {
                throw KQuicError.InvalidChannel(
                    "Channel ${channelConfig.label} already exists with delivery ${existing.delivery}"
                )
            }
            return existing
        }
        return channelsByLabel.getOrPut(channelConfig.label) {
            createChannelWrapper(channelConfig).also {
                transport.openChannel(channelConfig)
                it.markOpen()
                emitChannel(it)
            }
        }
    }

    fun onSignal(handler: (KQuicSignal) -> Unit): KQuicSubscription = subscribe(signals, handler)
    fun onChannel(handler: (KQuicChannel) -> Unit): KQuicSubscription = subscribe(channels, handler)
    fun onConnectionState(handler: (KQuicPeerConnectionState) -> Unit): KQuicSubscription =
        subscribe(connectionState, handler)
    fun onStats(handler: (KQuicStats) -> Unit): KQuicSubscription = subscribe(stats, handler)

    fun close() {
        if (closed) return
        closed = true
        gatheringJobs.forEach { it.cancel() }
        identityAndGatheringJob?.cancel()
        connectionJob?.cancel()
        transportEventsJob.cancel()
        channelsByLabel.values.forEach { it.markClosed() }
        transport.close()
        _state.value = KQuicPeerConnectionState.CLOSED
    }

    fun dispose() {
        close()
        context.dispose()
    }

    /** Called only after TLS Finished and the remote QUIC transport parameters validate. */
    private suspend fun confirmHandshake(pair: KQuicCandidatePair, remoteIdentity: KQuicRemoteIdentity) {
        if (closed) return
        val verification = when (val security = config.security) {
            is KQuicSecurity.Explicit -> security.peerVerifier.verify(remoteIdentity)
            KQuicSecurity.EphemeralAuthenticatedSignaling -> {
                val expected = remoteIdentityPublicKeySha256
                if (expected != null && expected == remoteIdentity.publicKeySha256) {
                    KQuicVerificationResult.Accepted
                } else {
                    KQuicVerificationResult.Rejected(
                        "Remote public-key fingerprint does not match authenticated signaling"
                    )
                }
            }
        }
        when (verification) {
            KQuicVerificationResult.Accepted -> Unit
            is KQuicVerificationResult.Rejected -> {
                fail(KQuicError.AuthenticationFailed(verification.reason))
                return
            }
        }
        _stats.value = _stats.value.copy(selectedCandidatePair = pair)
        _state.value = KQuicPeerConnectionState.CONNECTED
    }

    internal fun fail(error: KQuicError) {
        if (closed || _state.value == KQuicPeerConnectionState.FAILED) return
        gatheringJobs.forEach { it.cancel() }
        identityAndGatheringJob?.cancel()
        connectionJob?.cancel()
        transportEventsJob.cancel()
        channelsByLabel.values.forEach { it.markClosed() }
        transport.close()
        _stats.value = _stats.value.copy(closeReason = error.message)
        _state.value = KQuicPeerConnectionState.FAILED
    }

    private suspend fun addLocalCandidate(candidate: KQuicCandidate) {
        stateMutex.withLock {
            if (localCandidates.add(candidate) && started) {
                if (localSignalSent) emitCandidateSnapshot()
                selectCandidatePairLocked()
            }
        }
    }

    private fun addRemoteCandidateLocked(candidate: KQuicCandidate) {
        remoteCandidates += candidate
    }

    private fun selectCandidatePairLocked() {
        if (localCandidates.isEmpty() || remoteCandidates.isEmpty()) return
        if (connectionJob != null || _state.value == KQuicPeerConnectionState.CONNECTED) return
        val selected = KQuicCandidateSelector.orderedPairs(localCandidates, remoteCandidates)
            .firstOrNull { it !in attemptedCandidatePairs }
            ?: return
        attemptedCandidatePairs += selected
        _stats.value = _stats.value.copy(selectedCandidatePair = selected)
        _state.value = KQuicPeerConnectionState.CONNECTING
        connectionJob = context.scope.launch {
            try {
                val identity = identityReady.await()
                val remoteIdentity = config.connectionTimeoutMs?.let { timeoutMs ->
                    withTimeoutOrNull(timeoutMs) {
                        transport.connect(selected, identity, config.quicRole)
                    } ?: throw KQuicError.ConnectionTimedOut(timeoutMs)
                } ?: transport.connect(selected, identity, config.quicRole)
                stateMutex.withLock {
                    _state.value = KQuicPeerConnectionState.AUTHENTICATING
                    confirmHandshake(selected, remoteIdentity)
                }
            } catch (error: KQuicError) {
                candidateAttemptFailed(error)
            } catch (error: CancellationException) {
                if (!closed) throw error
            } catch (error: Throwable) {
                candidateAttemptFailed(
                    KQuicError.TransportFailure(error.message ?: "QUIC transport failed")
                )
            }
        }
    }

    private suspend fun candidateAttemptFailed(error: KQuicError) {
        stateMutex.withLock {
            if (closed) return
            lastConnectionError = error
            connectionJob = null
            if (error is KQuicError.ConnectionTimedOut) {
                fail(error)
                return
            }
            selectCandidatePairLocked()
            failIfCandidatePairsExhaustedLocked()
        }
    }

    private fun failIfCandidatePairsExhaustedLocked() {
        if (!localGatheringComplete || !remoteGatheringComplete || connectionJob != null) return
        val hasUntriedPair = KQuicCandidateSelector.orderedPairs(localCandidates, remoteCandidates)
            .any { it !in attemptedCandidatePairs }
        if (!hasUntriedPair && attemptedCandidatePairs.isNotEmpty()) {
            fail(lastConnectionError ?: KQuicError.TransportFailure("No candidate pair connected"))
        }
    }

    private fun send(channel: KQuicChannel, bytes: ByteArray, text: Boolean): Boolean {
        ensureConnected()
        if (bytes.size > config.limits.maxMessageSize) {
            throw KQuicError.MessageTooLarge(bytes.size, config.limits.maxMessageSize)
        }
        if (channel.delivery == KQuicDelivery.UNRELIABLE && bytes.size > config.limits.maxDatagramSize) {
            _stats.value = _stats.value.copy(datagramsDropped = _stats.value.datagramsDropped + 1)
            return false
        }
        return transport.send(channel.label, bytes, text)
    }

    private fun closeChannel(channel: KQuicChannel) {
        transport.closeChannel(channel.label)
        channel.markClosed()
    }

    private fun createChannelWrapper(channelConfig: KQuicChannelConfig): KQuicChannel = KQuicChannel(
        context = context,
        label = channelConfig.label,
        delivery = channelConfig.delivery,
        send = { channel, payload, text -> send(channel, payload, text) },
        closeChannel = ::closeChannel
    )

    private fun handleTransportEvent(event: KQuicTransportEvent) {
        if (closed) return
        when (event) {
            is KQuicTransportEvent.IncomingChannel -> {
                channelsByLabel[event.config.label]?.let { existing ->
                    if (existing.delivery != event.config.delivery) {
                        fail(KQuicError.ProtocolViolation(
                            "Channel ${event.config.label} was reopened with a different delivery mode"
                        ))
                        return
                    }
                }
                val channel = channelsByLabel.getOrPut(event.config.label) {
                    createChannelWrapper(event.config)
                }
                channel.markOpen()
                emitChannel(channel)
            }
            is KQuicTransportEvent.BytesReceived ->
                channelsByLabel[event.label]?.emitBytes(event.payload)
                    ?: fail(KQuicError.ProtocolViolation("Payload for unknown channel ${event.label}"))
            is KQuicTransportEvent.TextReceived ->
                channelsByLabel[event.label]?.emitText(event.text)
                    ?: fail(KQuicError.ProtocolViolation("Payload for unknown channel ${event.label}"))
            is KQuicTransportEvent.ChannelClosed -> channelsByLabel[event.label]?.markClosed()
            is KQuicTransportEvent.Disconnected -> {
                if (_state.value == KQuicPeerConnectionState.FAILED) return
                _stats.value = _stats.value.copy(closeReason = event.reason)
                _state.value = KQuicPeerConnectionState.DISCONNECTED
            }
            is KQuicTransportEvent.Failed -> fail(event.error)
            is KQuicTransportEvent.StatsUpdated -> {
                val selectedPair = _stats.value.selectedCandidatePair
                _stats.value = KQuicStats(
                    bytesSent = event.stats.bytesSent,
                    bytesReceived = event.stats.bytesReceived,
                    packetsSent = event.stats.packetsSent,
                    packetsReceived = event.stats.packetsReceived,
                    packetsLost = event.stats.packetsLost,
                    datagramsDropped = event.stats.datagramsDropped,
                    minimumRttMs = event.stats.minimumRttMs,
                    smoothedRttMs = event.stats.smoothedRttMs,
                    congestionWindowBytes = event.stats.congestionWindowBytes,
                    bytesInFlight = event.stats.bytesInFlight,
                    ptoCount = event.stats.ptoCount,
                    streamsBlocked = event.stats.streamsBlocked,
                    selectedCandidatePair = selectedPair,
                    closeReason = _stats.value.closeReason
                )
            }
        }
    }

    private fun emitChannel(channel: KQuicChannel) {
        if (emittedChannelLabels.add(channel.label)) {
            _channels.tryEmit(channel)
        }
    }

    private fun ensureConnected() {
        if (_state.value != KQuicPeerConnectionState.CONNECTED) {
            throw KQuicError.TransportUnavailable()
        }
    }

    private fun <T> subscribe(flow: Flow<T>, handler: (T) -> Unit): KQuicSubscription {
        val job = context.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            flow.collect(handler)
        }
        return KQuicSubscription { job.cancel() }
    }

    private fun emitInitialSignal() {
        _signals.tryEmit(
            KQuicOffer(
                rendezvousId = config.rendezvousId,
                candidates = localCandidates.toList(),
                identityPublicKeySha256 = checkNotNull(localIdentityPublicKeySha256),
                endOfCandidates = localGatheringComplete
            )
        )
        localSignalSent = true
    }

    private suspend fun emitCandidateSnapshot() {
        _signals.emit(
            KQuicCandidateUpdate(
                rendezvousId = config.rendezvousId,
                candidates = localCandidates.toList(),
                endOfCandidates = localGatheringComplete
            )
        )
    }

    private fun setRemoteIdentityFingerprint(value: String) {
        val current = remoteIdentityPublicKeySha256
        if (current != null && current != value) {
            throw KQuicError.InvalidSignal("Remote identity fingerprint changed during rendezvous")
        }
        remoteIdentityPublicKeySha256 = value
    }

    private fun Throwable.toKQuicError(fallback: String): KQuicError = when (this) {
        is KQuicError -> this
        else -> KQuicError.TransportFailure(message ?: fallback)
    }

    private val KQuicConfig.quicRole: KQuicTransportRole
        get() = if (initiator) KQuicTransportRole.CLIENT else KQuicTransportRole.SERVER
}
