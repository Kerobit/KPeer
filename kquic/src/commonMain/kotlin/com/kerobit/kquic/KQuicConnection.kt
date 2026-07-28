package com.kerobit.kquic

import com.kerobit.kquic.internal.ConnectivityBehaviour
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * P2P QUIC facade. The app forwards [signals] to the remote peer and passes received
 * signals to [signal]. Candidate gathering and packet I/O remain behind platform drivers.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class KQuicConnection(
    private val context: KQuicContext,
    private val config: KQuicConfig,
    private val logger: KQuicLogger = NoOpKQuicLogger
) {
    private val _state = MutableStateFlow(KQuicPeerConnectionState.NEW)
    private val _stats = MutableStateFlow(KQuicStats())
    private val connectivity = ConnectivityBehaviour()
    private val stateMutex = Mutex()
    private val transport = context.transportFactory.create(config).also {
        if (it is KQuicDiagnosticsAware) it.diagnosticLogger = logger
    }
    private val signaler = KQuicSignaler(
        config = config,
        platformRuntime = context.platformRuntime,
        gatherCandidates = transport::gatherCandidates,
        logger = logger
    )
    private val channelRegistry = KQuicChannelRegistry(
        context = context,
        config = config,
        transport = transport,
        connectionState = { _state.value },
        fail = ::fail
    )
    private val transportEventsJob: Job
    private var connectionJob: Job? = null
    private var identityAndGatheringJob: Job? = null
    private val lifecycle = AtomicInt(LIFECYCLE_NEW)
    private val started: Boolean get() = lifecycle.load() == LIFECYCLE_STARTED
    private val closed: Boolean get() = lifecycle.load() == LIFECYCLE_CLOSED

    val signals: Flow<KQuicSignal> = signaler.signals
    val channels: Flow<KQuicChannel> = channelRegistry.channels
    val connectionState: Flow<KQuicPeerConnectionState> = _state.asStateFlow()
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
        if (!lifecycle.compareAndSet(LIFECYCLE_NEW, LIFECYCLE_STARTED)) return
        logger.info(
            "Starting role=${config.quicRole} gatheringTimeoutMs=" +
                "${config.candidateGatheringTimeoutMs} connectivityCheckTimeoutMs=" +
                "${config.connectivityCheckTimeoutMs} connectionTimeoutMs=${config.connectionTimeoutMs}"
        )
        transitionTo(KQuicPeerConnectionState.GATHERING)
        identityAndGatheringJob = context.scope.launch {
            try {
                signaler.start()
                stateMutex.withLock {
                    selectCandidatePairLocked()
                    failIfCandidatePairsExhaustedLocked()
                }
            } catch (error: CancellationException) {
                if (!closed) throw error
            } catch (error: Throwable) {
                signaler.failStart(error)
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
        if (!started) {
            if (remote is KQuicOffer && !config.initiator) {
                start()
            } else {
                throw KQuicError.InvalidSignal(
                    "Call start() before applying this signal"
                )
            }
        }
        if (remote is KQuicOffer && !config.initiator) {
            signaler.awaitReady()
        }
        stateMutex.withLock {
            signaler.apply(remote)
            selectCandidatePairLocked()
            failIfCandidatePairsExhaustedLocked()
        }
    }

    /**
     * Creates a logical channel without performing I/O. Before the handshake completes the
     * wrapper remains CONNECTING and is opened after 1-RTT keys become available.
     */
    fun createChannel(channelConfig: KQuicChannelConfig): KQuicChannel {
        if (closed) throw KQuicError.InvalidChannel("KQuicPeer is closed")
        return channelRegistry.create(channelConfig)
    }

    fun onSignal(handler: (KQuicSignal) -> Unit): KQuicSubscription = subscribe(signals, handler)
    fun onChannel(handler: (KQuicChannel) -> Unit): KQuicSubscription = subscribe(channels, handler)
    fun onConnectionState(handler: (KQuicPeerConnectionState) -> Unit): KQuicSubscription =
        subscribe(connectionState, handler)
    suspend fun getStats(): KQuicStats {
        transport.getStats()?.let(::recordStats)
        return _stats.value
    }

    fun close() {
        if (!markClosed()) return
        logger.info("Closing KQuic peer")
        identityAndGatheringJob?.cancel()
        connectionJob?.cancel()
        transportEventsJob.cancel()
        channelRegistry.closeAll()
        transport.close()
        signaler.close()
        transitionTo(KQuicPeerConnectionState.CLOSED)
    }

    /** Called only after TLS Finished and the remote QUIC transport parameters validate. */
    private suspend fun confirmHandshake(pair: KQuicCandidatePair, remoteIdentity: KQuicRemoteIdentity) {
        if (closed) return
        when (val verification = signaler.verify(remoteIdentity)) {
            KQuicVerificationResult.Accepted -> Unit
            is KQuicVerificationResult.Rejected -> {
                fail(KQuicError.AuthenticationFailed(verification.reason))
                return
            }
        }
        _stats.value = _stats.value.copy(selectedCandidatePair = pair)
        logger.info("QUIC handshake authenticated for ${pair.summary()}")
        transitionTo(KQuicPeerConnectionState.CONNECTED)
        channelRegistry.openPending()
    }

    internal fun fail(error: KQuicError) {
        if (closed || _state.value == KQuicPeerConnectionState.FAILED) return
        logger.error(
            "Connection failed error=${error.kind()} reason=${error.message.orEmpty()} " +
                "attemptedPairs=${connectivity.attemptCount} " +
                "selectedPair=${_stats.value.selectedCandidatePair?.summary() ?: "none"}"
        )
        identityAndGatheringJob?.cancel()
        connectionJob?.cancel()
        transportEventsJob.cancel()
        channelRegistry.closeAll()
        transport.close()
        signaler.close()
        _stats.value = _stats.value.copy(closeReason = error.message)
        transitionTo(KQuicPeerConnectionState.FAILED)
    }

    private fun selectCandidatePairLocked() {
        val localCandidates = signaler.localCandidates
        val remoteCandidates = signaler.remoteCandidates
        val localCredentials = signaler.localCredentials ?: return
        val remoteCredentials = signaler.remoteCredentials ?: return
        if (localCandidates.isEmpty() || remoteCandidates.isEmpty()) return
        if (connectionJob != null || _state.value == KQuicPeerConnectionState.CONNECTED) return
        val selected = connectivity.nextPair(localCandidates, remoteCandidates) ?: return
        _stats.value = _stats.value.copy(selectedCandidatePair = selected)
        logger.info(
            "Trying candidate pair ${connectivity.attemptCount}: " +
                "${selected.redactedSummary()}"
        )
        logger.debug("Candidate pair details: ${selected.summary()}")
        transitionTo(KQuicPeerConnectionState.CONNECTING)
        connectionJob = context.scope.launch {
            try {
                val identity = signaler.identity()
                val remoteIdentity = config.connectionTimeoutMs?.let { timeoutMs ->
                    withTimeoutOrNull(timeoutMs) {
                        transport.connect(
                            selected,
                            identity,
                            config.quicRole,
                            localCredentials,
                            remoteCredentials
                        )
                    } ?: throw KQuicError.ConnectionTimedOut(timeoutMs)
                } ?: transport.connect(
                    selected,
                    identity,
                    config.quicRole,
                    localCredentials,
                    remoteCredentials
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
            connectivity.failed(error)
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
        if (!signaler.localGatheringComplete ||
            !signaler.remoteGatheringComplete ||
            connectionJob != null
        ) {
            return
        }
        val localCandidates = signaler.localCandidates
        val remoteCandidates = signaler.remoteCandidates
        val hasUntriedPair = connectivity.hasUntriedPair(localCandidates, remoteCandidates)
        if (!hasUntriedPair && connectivity.attemptCount > 0) {
            logger.warn(
                "Candidate pairs exhausted attempted=${connectivity.attemptCount} " +
                    "local=${localCandidates.size} remote=${remoteCandidates.size}"
            )
            fail(
                connectivity.lastError
                    ?: KQuicError.TransportFailure("No candidate pair connected")
            )
        }
    }

    private fun handleTransportEvent(event: KQuicTransportEvent) {
        if (closed) return
        if (channelRegistry.handle(event)) return
        when (event) {
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
            is KQuicTransportEvent.IncomingChannel,
            is KQuicTransportEvent.BytesReceived,
            is KQuicTransportEvent.TextReceived,
            is KQuicTransportEvent.BufferedAmountDrained,
            is KQuicTransportEvent.ChannelClosed -> error("Handled by channel registry")
        }
    }

    private fun recordStats(stats: KQuicTransportStats) {
        _stats.value = KQuicStats(
            bytesSent = stats.bytesSent,
            bytesReceived = stats.bytesReceived,
            packetsSent = stats.packetsSent,
            packetsReceived = stats.packetsReceived,
            packetsLost = stats.packetsLost,
            datagramsDropped = stats.datagramsDropped,
            minimumRttMs = stats.minimumRttMs,
            smoothedRttMs = stats.smoothedRttMs,
            congestionWindowBytes = stats.congestionWindowBytes,
            bytesInFlight = stats.bytesInFlight,
            ptoCount = stats.ptoCount,
            streamsBlocked = stats.streamsBlocked,
            selectedCandidatePair = _stats.value.selectedCandidatePair,
            closeReason = _stats.value.closeReason
        )
    }

    private fun markClosed(): Boolean {
        while (true) {
            val current = lifecycle.load()
            if (current == LIFECYCLE_CLOSED) return false
            if (lifecycle.compareAndSet(current, LIFECYCLE_CLOSED)) return true
        }
    }

    private fun <T> subscribe(flow: Flow<T>, handler: (T) -> Unit): KQuicSubscription {
        val job = context.scope.launch(start = CoroutineStart.UNDISPATCHED) {
            flow.collect(handler)
        }
        return KQuicSubscription { job.cancel() }
    }

    private fun transitionTo(next: KQuicPeerConnectionState) {
        val previous = _state.value
        if (previous == next) return
        _state.value = next
        logger.info("State $previous -> $next")
    }

}

private const val LIFECYCLE_NEW = 0
private const val LIFECYCLE_STARTED = 1
private const val LIFECYCLE_CLOSED = 2
