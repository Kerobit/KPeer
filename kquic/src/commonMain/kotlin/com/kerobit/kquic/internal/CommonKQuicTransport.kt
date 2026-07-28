package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicCandidate
import com.kerobit.kquic.KQuicCandidatePair
import com.kerobit.kquic.KQuicChannelConfig
import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicConnectivityCredentials
import com.kerobit.kquic.KQuicDiagnosticsAware
import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicIdentity
import com.kerobit.kquic.KQuicLogger
import com.kerobit.kquic.KQuicRemoteIdentity
import com.kerobit.kquic.KQuicTransport
import com.kerobit.kquic.KQuicTransportEvent
import com.kerobit.kquic.KQuicTransportFactory
import com.kerobit.kquic.KQuicTransportRole
import com.kerobit.kquic.KQuicTransportStats
import com.kerobit.kquic.NoOpKQuicLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

internal class CommonKQuicTransportFactory(
    private val scope: CoroutineScope,
    private val platformRuntime: KQuicPlatformRuntime
) : KQuicTransportFactory {
    override fun create(config: KQuicConfig): KQuicTransport =
        CommonKQuicTransport(scope, platformRuntime, config)
}

/**
 * Connection-level adapter. Candidate sockets are transferred in through [ValidatedPath], then
 * all packet I/O and protocol state belong exclusively to [CommonQuicConnectionActor].
 */
private class CommonKQuicTransport(
    private val scope: CoroutineScope,
    private val platformRuntime: KQuicPlatformRuntime,
    private val config: KQuicConfig
) : KQuicTransport, KQuicDiagnosticsAware {
    private val mutableEvents = MutableSharedFlow<KQuicTransportEvent>(
        extraBufferCapacity = config.limits.maxBufferedPackets.coerceAtMost(1_024)
    )
    override val events: Flow<KQuicTransportEvent> = mutableEvents.asSharedFlow()

    private val candidateTransport = CandidateTransport(platformRuntime, config)
    private var actor: CommonQuicConnectionActor? = null
    private var closed = false

    override var diagnosticLogger: KQuicLogger = NoOpKQuicLogger
        set(value) {
            field = value
            candidateTransport.diagnosticLogger = value
        }

    override suspend fun gatherCandidates(): List<KQuicCandidate> =
        candidateTransport.gatherCandidates()

    override suspend fun connect(
        candidatePair: KQuicCandidatePair,
        identity: KQuicIdentity,
        role: KQuicTransportRole,
        localCredentials: KQuicConnectivityCredentials,
        remoteCredentials: KQuicConnectivityCredentials
    ): KQuicRemoteIdentity {
        check(!closed) { "KQuic transport is closed" }
        val path = candidateTransport.validate(candidatePair, role, localCredentials, remoteCredentials)
        val connectionActor = try {
            CommonQuicConnectionActor(
                parentScope = scope,
                socket = path.socket,
                remoteAddress = path.remoteAddress,
                role = role,
                identity = identity,
                config = config,
                platformRuntime = platformRuntime,
                logger = diagnosticLogger,
                emitBufferedAmountDrained = { label, bytes ->
                    mutableEvents.tryEmit(KQuicTransportEvent.BufferedAmountDrained(label, bytes))
                },
                emitEvent = { mutableEvents.emit(it) }
            )
        } catch (error: Throwable) {
            path.socket.close()
            throw error
        }
        actor = connectionActor
        return connectionActor.connect()
    }

    override fun openChannel(config: KQuicChannelConfig) {
        val connection = actor ?: throw KQuicError.TransportUnavailable()
        if (!connection.openChannel(config)) {
            throw KQuicError.InvalidChannel("KQuic send queue is full")
        }
    }

    override fun send(label: String, payload: ByteArray, text: Boolean): Boolean =
        actor?.send(label, payload, text) ?: false

    override fun closeChannel(label: String) {
        actor?.closeChannel(label)
    }

    override suspend fun getStats(): KQuicTransportStats? = actor?.getStats()

    override fun close() {
        if (closed) return
        closed = true
        actor?.close()
        candidateTransport.close()
    }
}
