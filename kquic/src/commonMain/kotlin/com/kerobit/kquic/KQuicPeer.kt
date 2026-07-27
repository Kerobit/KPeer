package com.kerobit.kquic

import com.kerobit.kquic.internal.KQuicCandidateSelector
import com.kerobit.kquic.internal.extractSubjectPublicKeyInfo
import com.kerobit.kquic.internal.toHex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    private val config: KQuicConfig,
    private val logger: KQuicLogger = NoOpKQuicLogger
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
    private val stateMutex = Mutex()
    private val transport = context.transportFactory.create(config).also {
        if (it is KQuicDiagnosticsAware) it.diagnosticLogger = logger
    }
    private val transportEventsJob: Job
    private val identityReady = CompletableDeferred<KQuicIdentity>()
    private val gatheringReady = CompletableDeferred<Unit>()
    private var connectionJob: Job? = null
    private var identityAndGatheringJob: Job? = null
    private var localCredentials: KQuicConnectivityCredentials? = null
    private var remoteCredentials: KQuicConnectivityCredentials? = null
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
        logger.info(
            "Starting role=${config.quicRole} gatheringTimeoutMs=" +
                "${config.candidateGatheringTimeoutMs} connectivityCheckTimeoutMs=" +
                "${config.connectivityCheckTimeoutMs} connectionTimeoutMs=${config.connectionTimeoutMs}"
        )
        transitionTo(KQuicPeerConnectionState.GATHERING)
        started = true
        identityAndGatheringJob = context.scope.launch {
            try {
                localCredentials = createConnectivityCredentials()
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

                transport.gatherCandidates().forEach(localCandidates::add)
                stateMutex.withLock {
                    localGatheringComplete = true
                    logger.info("Local candidate gathering complete: ${localCandidates.summary()}")
                    logger.debug("Local candidates: ${localCandidates.debugSummary()}")
                    if (!gatheringReady.isCompleted) gatheringReady.complete(Unit)
                    if (config.initiator && !localSignalSent) {
                        emitInitialSignal()
                    }
                    selectCandidatePairLocked()
                    failIfCandidatePairsExhaustedLocked()
                }
            } catch (error: CancellationException) {
                if (!closed) throw error
            } catch (error: Throwable) {
                if (!identityReady.isCompleted) identityReady.completeExceptionally(error)
                if (!gatheringReady.isCompleted) gatheringReady.completeExceptionally(error)
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
        logger.info("Received ${remote.signalSummary()}")
        logger.debug("Remote candidates in signal: ${remote.candidates().debugSummary()}")
        if (!started) start()
        if (remote is KQuicOffer && !config.initiator) {
            identityReady.await()
            gatheringReady.await()
        }
        stateMutex.withLock {
            when (remote) {
                is KQuicOffer -> {
                    if (config.initiator) throw KQuicError.InvalidSignal("Initiator received a KQuicOffer")
                    setRemoteIdentityFingerprint(remote.identityPublicKeySha256)
                    setRemoteConnectivityCredentials(remote.connectivityCredentials)
                    remote.candidates.forEach(::addRemoteCandidateLocked)
                    remoteGatheringComplete = remote.endOfCandidates
                    _signals.emit(
                        KQuicAnswer(
                            rendezvousId = config.rendezvousId,
                            candidates = localCandidates.toList(),
                            identityPublicKeySha256 = checkNotNull(localIdentityPublicKeySha256),
                            connectivityCredentials = checkNotNull(localCredentials),
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
                    setRemoteConnectivityCredentials(remote.connectivityCredentials)
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
        logger.info("Closing KQuic peer")
        closed = true
        identityAndGatheringJob?.cancel()
        connectionJob?.cancel()
        transportEventsJob.cancel()
        channelsByLabel.values.forEach { it.markClosed() }
        transport.close()
        transitionTo(KQuicPeerConnectionState.CLOSED)
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
        logger.info("QUIC handshake authenticated for ${pair.summary()}")
        transitionTo(KQuicPeerConnectionState.CONNECTED)
    }

    internal fun fail(error: KQuicError) {
        if (closed || _state.value == KQuicPeerConnectionState.FAILED) return
        logger.error(
            "Connection failed error=${error.kind()} reason=${error.message.orEmpty()} " +
                "attemptedPairs=${attemptedCandidatePairs.size} " +
                "selectedPair=${_stats.value.selectedCandidatePair?.summary() ?: "none"}"
        )
        identityAndGatheringJob?.cancel()
        connectionJob?.cancel()
        transportEventsJob.cancel()
        channelsByLabel.values.forEach { it.markClosed() }
        transport.close()
        _stats.value = _stats.value.copy(closeReason = error.message)
        transitionTo(KQuicPeerConnectionState.FAILED)
    }

    private fun addRemoteCandidateLocked(candidate: KQuicCandidate) {
        if (candidate.type == KQuicCandidateType.RELAY) {
            throw KQuicError.InvalidSignal("Relay candidates are not supported by this prototype")
        }
        remoteCandidates += candidate
    }

    private fun selectCandidatePairLocked() {
        if (localCandidates.isEmpty() || remoteCandidates.isEmpty()) return
        if (localCredentials == null || remoteCredentials == null) return
        if (connectionJob != null || _state.value == KQuicPeerConnectionState.CONNECTED) return
        val selected = KQuicCandidateSelector.orderedPairs(localCandidates, remoteCandidates)
            .firstOrNull { it !in attemptedCandidatePairs }
            ?: return
        attemptedCandidatePairs += selected
        _stats.value = _stats.value.copy(selectedCandidatePair = selected)
        logger.info(
            "Trying candidate pair ${attemptedCandidatePairs.size}: " +
                "${selected.redactedSummary()}"
        )
        logger.debug("Candidate pair details: ${selected.summary()}")
        transitionTo(KQuicPeerConnectionState.CONNECTING)
        connectionJob = context.scope.launch {
            try {
                val identity = identityReady.await()
                val localConnectivity = checkNotNull(localCredentials)
                val remoteConnectivity = checkNotNull(remoteCredentials)
                val remoteIdentity = config.connectionTimeoutMs?.let { timeoutMs ->
                    withTimeoutOrNull(timeoutMs) {
                        transport.connect(
                            selected,
                            identity,
                            config.quicRole,
                            localConnectivity,
                            remoteConnectivity
                        )
                    } ?: throw KQuicError.ConnectionTimedOut(timeoutMs)
                } ?: transport.connect(
                    selected,
                    identity,
                    config.quicRole,
                    localConnectivity,
                    remoteConnectivity
                )
                stateMutex.withLock {
                    logger.info("Connectivity and QUIC handshake completed; verifying peer identity")
                    transitionTo(KQuicPeerConnectionState.AUTHENTICATING)
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
            logger.warn(
                "Candidate pair failed error=${error.kind()} reason=${error.message.orEmpty()} " +
                    "pair=${_stats.value.selectedCandidatePair?.redactedSummary() ?: "none"}"
            )
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
            logger.warn(
                "Candidate pairs exhausted attempted=${attemptedCandidatePairs.size} " +
                    "local=${localCandidates.size} remote=${remoteCandidates.size}"
            )
            fail(lastConnectionError ?: KQuicError.TransportFailure("No candidate pair connected"))
        }
    }

    private fun send(channel: KQuicChannel, bytes: ByteArray, text: Boolean): Boolean {
        ensureConnected()
        if (bytes.size > config.limits.maxMessageSize) {
            throw KQuicError.MessageTooLarge(bytes.size, config.limits.maxMessageSize)
        }
        if (channel.delivery == KQuicDelivery.UNRELIABLE && bytes.size > config.limits.maxDatagramSize) {
            throw KQuicError.MessageTooLarge(bytes.size, config.limits.maxDatagramSize)
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
                logger.warn("Transport disconnected reason=${event.reason ?: "none"}")
                _stats.value = _stats.value.copy(closeReason = event.reason)
                transitionTo(KQuicPeerConnectionState.DISCONNECTED)
            }
            is KQuicTransportEvent.Failed -> {
                logger.error(
                    "Transport event failed error=${event.error.kind()} " +
                        "reason=${event.error.message.orEmpty()}"
                )
                fail(event.error)
            }
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
                connectivityCredentials = checkNotNull(localCredentials),
                endOfCandidates = localGatheringComplete
            )
        )
        logger.info(
            "Emitted offer candidates=${localCandidates.size} " +
                "endOfCandidates=$localGatheringComplete"
        )
        localSignalSent = true
    }

    private fun setRemoteIdentityFingerprint(value: String) {
        val current = remoteIdentityPublicKeySha256
        if (current != null && current != value) {
            throw KQuicError.InvalidSignal("Remote identity fingerprint changed during rendezvous")
        }
        remoteIdentityPublicKeySha256 = value
    }

    private fun setRemoteConnectivityCredentials(value: KQuicConnectivityCredentials) {
        val current = remoteCredentials
        if (current != null && current != value) {
            throw KQuicError.InvalidSignal(
                "Remote connectivity credentials changed during rendezvous"
            )
        }
        remoteCredentials = value
    }

    private fun createConnectivityCredentials(): KQuicConnectivityCredentials =
        KQuicConnectivityCredentials(
            usernameFragment = context.platformServices.secureRandom(8).toHex(),
            password = context.platformServices.secureRandom(24).toHex()
        )

    private fun Throwable.toKQuicError(fallback: String): KQuicError = when (this) {
        is KQuicError -> this
        else -> KQuicError.TransportFailure(message ?: fallback)
    }

    private val KQuicConfig.quicRole: KQuicTransportRole
        get() = if (initiator) KQuicTransportRole.CLIENT else KQuicTransportRole.SERVER

    private fun transitionTo(next: KQuicPeerConnectionState) {
        val previous = _state.value
        if (previous == next) return
        _state.value = next
        logger.info("State $previous -> $next")
    }

    private fun KQuicSignal.signalSummary(): String = when (this) {
        is KQuicOffer ->
            "offer candidates=${candidates.size} endOfCandidates=$endOfCandidates"
        is KQuicAnswer ->
            "answer candidates=${candidates.size} endOfCandidates=$endOfCandidates"
        is KQuicCandidateUpdate ->
            "candidate update candidates=${candidates.size} endOfCandidates=$endOfCandidates"
        is KQuicEndOfCandidates -> "end of candidates"
    }

    private fun KQuicSignal.candidates(): List<KQuicCandidate> = when (this) {
        is KQuicOffer -> candidates
        is KQuicAnswer -> candidates
        is KQuicCandidateUpdate -> candidates
        is KQuicEndOfCandidates -> emptyList()
    }

    private fun Collection<KQuicCandidate>.summary(): String =
        KQuicCandidateType.entries.joinToString(separator = " ") { type ->
            "${type.name.lowercase()}=${count { it.type == type }}"
        } + " total=$size"

    private fun Collection<KQuicCandidate>.debugSummary(): String =
        if (isEmpty()) "none" else joinToString { it.summary() }

    private fun KQuicCandidate.summary(): String =
        "${type.name.lowercase()}:$host:$port foundation=$foundation priority=$priority"

    private fun KQuicCandidatePair.summary(): String =
        "local=[${local.summary()}] remote=[${remote.summary()}]"

    private fun KQuicCandidatePair.redactedSummary(): String =
        "local=${local.type.name.lowercase()}/${local.foundation} " +
            "remote=${remote.type.name.lowercase()}/${remote.foundation}"

    private fun KQuicError.kind(): String = when (this) {
        is KQuicError.InvalidSignal -> "InvalidSignal"
        is KQuicError.InvalidChannel -> "InvalidChannel"
        is KQuicError.CandidateGatheringFailed -> "CandidateGatheringFailed"
        is KQuicError.ConnectivityCheckFailed -> "ConnectivityCheckFailed"
        is KQuicError.NoViableCandidatePair -> "NoViableCandidatePair"
        is KQuicError.UnsupportedDelivery -> "UnsupportedDelivery"
        is KQuicError.AuthenticationFailed -> "AuthenticationFailed"
        is KQuicError.ProtocolViolation -> "ProtocolViolation"
        is KQuicError.TransportFailure -> "TransportFailure"
        is KQuicError.ConnectionTimedOut -> "ConnectionTimedOut"
        is KQuicError.MessageTooLarge -> "MessageTooLarge"
        is KQuicError.TransportUnavailable -> "TransportUnavailable"
    }
}
