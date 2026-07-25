package com.kerobit.kquic

import com.kerobit.kquic.internal.KQuicCandidateSelector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * P2P QUIC facade. The app forwards [signals] to the remote peer and passes received
 * signals to [signal]. Candidate gathering and packet I/O remain behind platform drivers.
 */
@ExperimentalKQuicApi
class KQuicPeer(
    private val context: KQuicContext,
    private val config: KQuicConfig
) {
    private val _signals = MutableSharedFlow<KQuicSignal>(extraBufferCapacity = 64)
    private val _channels = MutableSharedFlow<KQuicChannel>(extraBufferCapacity = 16)
    private val _state = MutableStateFlow(KQuicPeerConnectionState.NEW)
    private val _stats = MutableStateFlow(KQuicStats())
    private val localCandidates = linkedSetOf<KQuicCandidate>()
    private val remoteCandidates = linkedSetOf<KQuicCandidate>()
    private val channelsByLabel = linkedMapOf<String, KQuicChannel>()
    private val emittedChannelLabels = mutableSetOf<String>()
    private val gatheringJobs = mutableListOf<Job>()
    private val transport = context.transportFactory.create(config)
    private val transportEventsJob: Job
    private var connectionJob: Job? = null
    private var started = false
    private var closed = false

    val signals: Flow<KQuicSignal> = _signals.asSharedFlow()
    val channels: Flow<KQuicChannel> = _channels.asSharedFlow()
    val connectionState: Flow<KQuicPeerConnectionState> = _state.asStateFlow()
    val stats: Flow<KQuicStats> = _stats.asStateFlow()
    val currentConnectionState: KQuicPeerConnectionState get() = _state.value

    init {
        transportEventsJob = context.scope.launch {
            transport.events.collect(::handleTransportEvent)
        }
    }

    /** Starts local candidate gathering and emits an offer from the signaling initiator. */
    fun start() {
        if (started || closed) return
        _state.value = KQuicPeerConnectionState.GATHERING
        localCandidates += config.initialCandidates
        started = true
        config.candidateSources.forEach { source ->
            gatheringJobs += context.scope.launch {
                source.candidates().collect(::addLocalCandidate)
            }
        }
        if (config.initiator) {
            _signals.tryEmit(KQuicOffer(config.rendezvousId, localCandidates.toList()))
        }
    }

    /** Applies an app-delivered rendezvous message. It never performs application signaling itself. */
    suspend fun signal(remote: KQuicSignal) {
        if (closed) throw KQuicError.InvalidSignal("KQuicPeer is closed")
        if (remote.rendezvousId != config.rendezvousId) {
            throw KQuicError.InvalidSignal("Signal rendezvousId does not match this peer")
        }
        if (!started) start()
        when (remote) {
            is KQuicOffer -> {
                if (config.initiator) throw KQuicError.InvalidSignal("Initiator received a KQuicOffer")
                remote.candidates.forEach(::addRemoteCandidate)
                _signals.emit(KQuicAnswer(config.rendezvousId, localCandidates.toList()))
            }
            is KQuicAnswer -> {
                if (!config.initiator) throw KQuicError.InvalidSignal("Responder received a KQuicAnswer")
                remote.candidates.forEach(::addRemoteCandidate)
            }
            is KQuicCandidateUpdate -> addRemoteCandidate(remote.candidate)
            is KQuicEndOfCandidates -> selectCandidatePair()
        }
        selectCandidatePair()
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

    fun close() {
        if (closed) return
        closed = true
        gatheringJobs.forEach { it.cancel() }
        connectionJob?.cancel()
        transportEventsJob.cancel()
        channelsByLabel.values.forEach { it.markClosed() }
        transport.close()
        _state.value = KQuicPeerConnectionState.CLOSED
    }

    /** Called by the platform packet driver before serializing the TLS Certificate message. */
    internal suspend fun localCredentials(): KQuicCredentials = config.credentialsProvider.credentialsFor(
        KQuicHandshakeRequest(config.rendezvousId, config.initiator)
    )

    /** Called only after TLS Finished and the remote QUIC transport parameters validate. */
    private suspend fun confirmHandshake(pair: KQuicCandidatePair, remoteIdentity: KQuicRemoteIdentity) {
        if (closed) return
        when (val verification = config.peerVerifier.verify(remoteIdentity)) {
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
        connectionJob?.cancel()
        transportEventsJob.cancel()
        channelsByLabel.values.forEach { it.markClosed() }
        transport.close()
        _stats.value = _stats.value.copy(closeReason = error.message)
        _state.value = KQuicPeerConnectionState.FAILED
    }

    private fun addLocalCandidate(candidate: KQuicCandidate) {
        if (localCandidates.add(candidate) && started) {
            _signals.tryEmit(KQuicCandidateUpdate(config.rendezvousId, candidate))
        }
    }

    private fun addRemoteCandidate(candidate: KQuicCandidate) {
        remoteCandidates += candidate
    }

    private fun selectCandidatePair() {
        if (localCandidates.isEmpty() || remoteCandidates.isEmpty()) return
        if (connectionJob != null || _state.value == KQuicPeerConnectionState.CONNECTED) return
        val selected = KQuicCandidateSelector.orderedPairs(localCandidates, remoteCandidates).firstOrNull()
            ?: return
        _stats.value = _stats.value.copy(selectedCandidatePair = selected)
        _state.value = KQuicPeerConnectionState.CONNECTING
        connectionJob = context.scope.launch {
            try {
                val credentials = localCredentials()
                val remoteIdentity = config.connectionTimeoutMs?.let { timeoutMs ->
                    withTimeoutOrNull(timeoutMs) {
                        transport.connect(selected, credentials)
                    } ?: throw KQuicError.ConnectionTimedOut(timeoutMs)
                } ?: transport.connect(selected, credentials)
                confirmHandshake(selected, remoteIdentity)
            } catch (error: KQuicError) {
                fail(error)
            } catch (error: CancellationException) {
                if (!closed) throw error
            } catch (error: Throwable) {
                fail(KQuicError.TransportFailure(error.message ?: "QUIC transport failed"))
            }
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
                    smoothedRttMs = event.stats.smoothedRttMs,
                    bytesInFlight = event.stats.bytesInFlight,
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
        val job = context.scope.launch { flow.collect(handler) }
        return KQuicSubscription { job.cancel() }
    }
}
