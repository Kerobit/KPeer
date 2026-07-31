package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicChannelConfig
import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicDelivery
import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicIdentity
import com.kerobit.kquic.KQuicLogger
import com.kerobit.kquic.KQuicRemoteIdentity
import com.kerobit.kquic.KQuicTransportEvent
import com.kerobit.kquic.KQuicTransportRole
import com.kerobit.kquic.KQuicTransportStats
import com.kerobit.kquic.NoOpKQuicLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

/**
 * Serial QUIC v1 connection actor. UDP jobs only enqueue commands; all protocol state is owned by
 * [runActor], which prevents packet numbers, recovery, TLS and stream state from racing.
 */
internal class CommonQuicConnectionActor(
    parentScope: CoroutineScope,
    socket: KQuicDatagramSocket,
    private val remoteAddress: KQuicSocketAddress,
    private val role: KQuicTransportRole,
    private val identity: KQuicIdentity,
    private val config: KQuicConfig,
    private val platformRuntime: KQuicPlatformRuntime,
    private val emitEvent: suspend (KQuicTransportEvent) -> Unit,
    private val emitBufferedAmountDrained: (String, Int) -> Unit = { _, _ -> },
    private val logger: KQuicLogger = NoOpKQuicLogger
) {
    private sealed interface ActorCommand {
        data class Core(val command: QuicConnectionCore.Command) : ActorCommand
        data class GetStats(
            val reply: CompletableDeferred<KQuicTransportStats>
        ) : ActorCommand
        data class Fatal(val error: Throwable) : ActorCommand
    }

    private val scopeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + scopeJob)
    private val commands = Channel<ActorCommand>(capacity = config.limits.maxBufferedPackets.coerceIn(16, 1_024))
    private val connected = CompletableDeferred<KQuicRemoteIdentity>()
    private val crypto = platformRuntime.crypto.packetProtection
    private val handshakeCrypto = platformRuntime.crypto.handshakeCrypto
    private val packetProtection = QuicPacketProtection(crypto)
    private val keySchedule = Tls13KeySchedule(handshakeCrypto.primitives)
    private val endpointRole = if (role == KQuicTransportRole.CLIENT) {
        QuicEndpointRole.CLIENT
    } else {
        QuicEndpointRole.SERVER
    }
    private val localConnectionId = platformRuntime.random.bytes(CONNECTION_ID_LENGTH)
    private var originalDestinationConnectionId: ByteArray? =
        if (endpointRole == QuicEndpointRole.CLIENT) {
            platformRuntime.random.bytes(CONNECTION_ID_LENGTH)
        } else {
            null
        }
    private var remoteConnectionId: ByteArray? = null
    private val handshake = QuicHandshake(
        endpointRole = endpointRole,
        identity = identity,
        config = config,
        handshakeCrypto = handshakeCrypto,
        localConnectionId = localConnectionId
    )
    private var handshakeConfirmed = false
    private val core = QuicConnectionCore(
        startedAtMs = nowMs(),
        idleTimeoutMs = config.idleTimeoutMs,
        networkCheckIntervalMs = NETWORK_CHECK_INTERVAL_MS
    )
    private val closing: Boolean get() = core.closing
    private val ioDriver = QuicIoDriver(
        scope = scope,
        socket = socket,
        isClosing = { core.closing },
        onInbound = {
            if (!StunProtocol.isStun(it.payload) && it.source == remoteAddress) {
                commands.send(ActorCommand.Core(QuicConnectionCore.Command.Receive(it, nowMs())))
            }
        },
        onTick = {
            commands.send(ActorCommand.Core(QuicConnectionCore.Command.Deadline(nowMs())))
        },
        onFailure = { commands.trySend(ActorCommand.Fatal(it)) }
    )
    private val initialHostAddresses = platformRuntime.network.hostAddresses().sorted()

    private val packetSpaces = QuicPacketSpaces()
    private val pacer = QuicPacer()
    private var queuedFrameBytes = 0
    private val channelManager = QuicChannelManager(
        endpointRole = endpointRole,
        config = config,
        queuedFrameBytes = { queuedFrameBytes },
        enqueue = { frame, required ->
            enqueue(QuicEncryptionLevel.ONE_RTT, frame, required)
        },
        bufferedAmountDrained = emitBufferedAmountDrained,
        emitEvent = ::emitCoreEvent
    )

    suspend fun connect(): KQuicRemoteIdentity {
        if (!core.started) start()
        return connected.await()
    }

    fun openChannel(config: KQuicChannelConfig): Boolean {
        if (config.delivery == KQuicDelivery.UNRELIABLE &&
            handshake.remoteParameters?.maxDatagramFrameSize == null
        ) {
            throw KQuicError.UnsupportedDelivery("The remote peer did not negotiate QUIC DATAGRAM")
        }
        return commands.trySend(
            ActorCommand.Core(QuicConnectionCore.Command.OpenChannel(config))
        ).isSuccess
    }

    fun send(label: String, payload: ByteArray, text: Boolean): Boolean {
        val reservation = channelManager.reserve(payload.size) ?: return false
        val command = QuicConnectionCore.Command.Send(label, payload.copyOf(), text, reservation)
        return commands.trySend(ActorCommand.Core(command)).isSuccess.also { accepted ->
            if (!accepted) channelManager.release(reservation)
        }
    }

    fun closeChannel(label: String) {
        commands.trySend(ActorCommand.Core(QuicConnectionCore.Command.CloseChannel(label)))
    }

    suspend fun getStats(): KQuicTransportStats {
        if (closing) return currentStats()
        val reply = CompletableDeferred<KQuicTransportStats>()
        if (commands.trySend(ActorCommand.GetStats(reply)).isFailure) return currentStats()
        return select {
            reply.onAwait { it }
            scopeJob.onJoin { currentStats() }
        }
    }

    fun close() {
        val command = ActorCommand.Core(QuicConnectionCore.Command.Close("application_close"))
        if (commands.trySend(command).isFailure) {
            ioDriver.close()
            scopeJob.cancel()
        }
    }

    private fun start() {
        if (!core.start()) return
        logger.info(
            "Starting QUIC handshake role=$role idleTimeoutMs=${config.idleTimeoutMs}"
        )
        logger.debug("QUIC remote endpoint=$remoteAddress")
        scope.launch { runActor() }
        ioDriver.start()
        if (endpointRole == QuicEndpointRole.CLIENT) {
            commands.trySend(ActorCommand.Core(QuicConnectionCore.Command.Deadline(nowMs())))
        }
    }

    private suspend fun runActor() {
        try {
            if (endpointRole == QuicEndpointRole.CLIENT) initializeClient()
            scheduleNextTick(flush())
            for (command in commands) {
                when (command) {
                    is ActorCommand.Core -> applyCoreCommand(command.command)
                    is ActorCommand.GetStats -> command.reply.complete(currentStats())
                    is ActorCommand.Fatal -> throw command.error
                }
                scheduleNextTick(flush())
                if (closing) return
            }
        } catch (error: CancellationException) {
            if (!closing) throw error
        } catch (error: Throwable) {
            val mapped = error as? KQuicError
                ?: KQuicError.TransportFailure(error.message ?: "QUIC actor failed")
            logger.error(
                "QUIC actor failed reason=${mapped.message.orEmpty()} " +
                    "packetsSent=${core.stats().packetsSent} " +
                    "packetsReceived=${core.stats().packetsReceived} " +
                    "bytesSent=${core.stats().bytesSent} bytesReceived=${core.stats().bytesReceived}"
            )
            if (!connected.isCompleted) connected.completeExceptionally(mapped)
            emitCoreEvent(KQuicTransportEvent.Failed(mapped))
        } finally {
            core.close("actor_terminated")
            handshake.close()
            packetSpaces.values.mapNotNull { it.protection }.forEach { pair ->
                pair.read.clear()
                pair.write.clear()
            }
            packetSpaces.values.forEach { it.protection = null }
            channelManager.reset()
            ioDriver.close()
            commands.close()
            scopeJob.cancel()
        }
    }

    private suspend fun initializeClient() {
        val destinationId = checkNotNull(originalDestinationConnectionId)
        installInitialKeys(destinationId)
        handshake.initialize(originalDestinationConnectionId)
        handleTlsEvents(handshake.start())
    }

    private fun initializeServer(invariant: QuicInvariantHeader.Long) {
        originalDestinationConnectionId = invariant.destinationConnectionId
        remoteConnectionId = invariant.sourceConnectionId
        installInitialKeys(invariant.destinationConnectionId)
        handshake.initialize(originalDestinationConnectionId)
    }

    private fun installInitialKeys(destinationId: ByteArray) {
        val initial = QuicInitialCrypto(crypto)
        packetSpaces[QuicEncryptionLevel.INITIAL].protection = if (
            endpointRole == QuicEndpointRole.CLIENT
        ) {
            QuicProtectionPair(
                read = initial.derive(destinationId, QuicInitialRole.SERVER),
                write = initial.derive(destinationId, QuicInitialRole.CLIENT)
            )
        } else {
            QuicProtectionPair(
                read = initial.derive(destinationId, QuicInitialRole.CLIENT),
                write = initial.derive(destinationId, QuicInitialRole.SERVER)
            )
        }
    }

    private suspend fun applyCoreCommand(command: QuicConnectionCore.Command) {
        if (command is QuicConnectionCore.Command.Deadline) {
            handleTick(command.nowMs)
            return
        }
        core.command(command).forEach { applyCoreEffect(it) }
    }

    private suspend fun applyCoreEffect(effect: QuicConnectionCore.Effect) {
        when (effect) {
            is QuicConnectionCore.Effect.DatagramReady -> handleInbound(effect.datagram)
            is QuicConnectionCore.Effect.OpenChannelRequested ->
                handleOpenChannel(effect.config)
            is QuicConnectionCore.Effect.SendRequested -> handleSend(effect)
            is QuicConnectionCore.Effect.CloseChannelRequested ->
                handleCloseChannel(effect.label)
            is QuicConnectionCore.Effect.CloseRequested -> gracefulClose(effect.reason)
            is QuicConnectionCore.Effect.EventReady -> emitEvent(effect.event)
            QuicConnectionCore.Effect.IdleTimeout ->
                throw KQuicError.TransportFailure(
                    "QUIC idle timeout after ${config.idleTimeoutMs} ms"
                )
            QuicConnectionCore.Effect.CheckNetwork -> {
                if (platformRuntime.network.hostAddresses().sorted() != initialHostAddresses) {
                    gracefulClose("NETWORK_CHANGED")
                }
            }
            is QuicConnectionCore.Effect.DatagramToSend ->
                ioDriver.send(effect.destination, effect.payload)
        }
    }

    private suspend fun emitCoreEvent(event: KQuicTransportEvent) {
        applyCoreEffect(core.event(event))
    }

    private suspend fun handleInbound(datagram: KQuicInboundDatagram) {
        val invariant = QuicInvariantHeaders.decode(datagram.payload, shortHeaderDestinationIdLength = localConnectionId.size)
        if (endpointRole == QuicEndpointRole.SERVER && !handshake.initialized) {
            logger.debug("Received first QUIC Initial datagram from ${datagram.source}")
            val long = invariant as? QuicInvariantHeader.Long
                ?: throw KQuicError.ProtocolViolation("First QUIC packet must use a long header")
            if (datagram.payload.size < QuicInitialDatagrams.MINIMUM_SIZE) {
                throw KQuicError.ProtocolViolation("First Initial datagram is smaller than 1200 bytes")
            }
            initializeServer(long)
        }
        validateDestinationConnectionId(invariant)
        if (invariant is QuicInvariantHeader.Long && remoteConnectionId == null) {
            remoteConnectionId = invariant.sourceConnectionId
        }
        val level = when (invariant) {
            is QuicInvariantHeader.Long -> when ((invariant.firstByte ushr 4) and 0x03) {
                0 -> QuicEncryptionLevel.INITIAL
                2 -> QuicEncryptionLevel.HANDSHAKE
                else -> throw KQuicError.ProtocolViolation("Unsupported long-header packet")
            }
            is QuicInvariantHeader.Short -> QuicEncryptionLevel.ONE_RTT
        }
        val packetSpace = packetSpaces[level]
        val keys = packetSpace.protection?.read ?: return
        val opened = when (invariant) {
            is QuicInvariantHeader.Long -> packetProtection.openLong(datagram.payload, packetSpace.largestReceived, keys)
            is QuicInvariantHeader.Short -> packetProtection.openShort(
                datagram.payload,
                localConnectionId.size,
                packetSpace.largestReceived,
                keys
            )
        }
        val frames = QuicFrames.decodeAll(opened.plaintext)
        val ackEliciting = frames.any { it.isAckEliciting() }
        val now = nowMs()
        val isNew = packetSpace.ackTracker.onPacketReceived(opened.packetNumber, now, ackEliciting)
        if (!isNew) return
        packetSpace.largestReceived = maxOf(packetSpace.largestReceived ?: 0uL, opened.packetNumber)
        frames.forEach { processFrame(level, it) }
        val tracker = packetSpace.ackTracker
        if (tracker.shouldAcknowledge()) {
            tracker.frame(now, LOCAL_ACK_DELAY_EXPONENT)?.let {
                enqueue(level, it, required = true)
            }
        }
    }

    private suspend fun processFrame(level: QuicEncryptionLevel, frame: QuicFrame) {
        when (frame) {
            is QuicFrame.Ack -> processAck(level, frame)
            is QuicFrame.Crypto -> {
                handleTlsEvents(handshake.receive(level, frame))
            }
            is QuicFrame.Stream -> channelManager.processStream(frame)
            is QuicFrame.Datagram -> channelManager.processDatagram(frame)
            is QuicFrame.MaxStreams ->
                channelManager.updatePeerMaxStreams(frame.bidirectional, frame.maximumStreams)
            is QuicFrame.MaxStreamData ->
                channelManager.updatePeerMaxStreamData(frame.streamId, frame.maximumStreamData)
            is QuicFrame.MaxData -> channelManager.updatePeerMaxData(frame.maximumData)
            is QuicFrame.ResetStream -> channelManager.streamReset(frame.streamId)
            is QuicFrame.StopSending -> channelManager.streamReset(frame.streamId)
            is QuicFrame.ConnectionClose -> {
                emitCoreEvent(KQuicTransportEvent.Disconnected(frame.reason))
                core.close(frame.reason)
                scopeJob.cancel()
            }
            QuicFrame.HandshakeDone -> {
                if (endpointRole != QuicEndpointRole.CLIENT || !handshake.complete) {
                    throw KQuicError.ProtocolViolation("Unexpected HANDSHAKE_DONE")
                }
                handshakeConfirmed = true
                completeConnection()
                discardKeys(QuicEncryptionLevel.INITIAL)
                discardKeys(QuicEncryptionLevel.HANDSHAKE)
                packetSpaces[QuicPacketNumberSpace.INITIAL].discardRecovery()
                packetSpaces[QuicPacketNumberSpace.HANDSHAKE].discardRecovery()
            }
            is QuicFrame.NewConnectionId,
            is QuicFrame.RetireConnectionId -> Unit
            is QuicFrame.DataBlocked -> channelManager.peerBlockedOnConnection()
            is QuicFrame.StreamDataBlocked -> channelManager.peerBlockedOnStream(frame.streamId)
            is QuicFrame.StreamsBlocked -> channelManager.peerBlockedOnStreams()
            QuicFrame.Ping -> Unit
        }
    }

    private fun processAck(level: QuicEncryptionLevel, frame: QuicFrame.Ack) {
        val exponent =
            handshake.remoteParameters?.ackDelayExponent?.toInt()
                ?: LOCAL_ACK_DELAY_EXPONENT
        val delayMs = ((frame.ackDelay shl exponent) / 1_000uL).toLong()
        val result = packetSpaces[level].recovery.onAckReceivedDetailed(frame.ranges, nowMs(), delayMs)
        result.framesToRetransmit.forEach {
            enqueue(level, it, required = false)
        }
        channelManager.drain()
    }

    private suspend fun handleTlsEvents(events: List<QuicTlsEvent>) {
        events.forEach { event ->
            when (event) {
                is QuicTlsEvent.CryptoData -> {
                    handshake.outboundCryptoFrames(
                        event.level,
                        event.bytes,
                        MAX_FRAME_PAYLOAD
                    ).forEach {
                        enqueue(event.level, it, required = true)
                    }
                }
                is QuicTlsEvent.SecretsAvailable -> {
                    packetSpaces[event.level].protection = QuicProtectionPair(
                        read = keySchedule.quicPacketProtectionKeys(event.secrets.read),
                        write = keySchedule.quicPacketProtectionKeys(event.secrets.write)
                    )
                }
                is QuicTlsEvent.RemoteIdentityAvailable -> {
                    handshake.record(event)
                    logger.debug(
                        "TLS remote identity available fingerprint=" +
                            "${event.identity.publicKeySha256.take(8)}…"
                    )
                }
                is QuicTlsEvent.TransportParametersAvailable -> {
                    handshake.record(event)
                    applyRemoteParameters(event.parameters)
                    logger.debug("QUIC remote transport parameters accepted")
                }
                QuicTlsEvent.HandshakeComplete -> {
                    handshake.record(event)
                    logger.info("TLS 1.3 handshake complete role=$role")
                    if (endpointRole == QuicEndpointRole.SERVER) {
                        enqueue(QuicEncryptionLevel.ONE_RTT, QuicFrame.HandshakeDone, true)
                    }
                }
                is QuicTlsEvent.Alert ->
                    throw KQuicError.AuthenticationFailed(event.reason)
            }
        }
    }

    private fun applyRemoteParameters(parameters: QuicTransportParameterValues) {
        val expectedRemoteId = remoteConnectionId
        if (expectedRemoteId != null &&
            !parameters.initialSourceConnectionId.contentEquals(expectedRemoteId)
        ) {
            throw KQuicError.ProtocolViolation("Remote initial_source_connection_id does not match packet headers")
        }
        if (endpointRole == QuicEndpointRole.CLIENT) {
            val expectedOriginal = checkNotNull(originalDestinationConnectionId)
            if (parameters.originalDestinationConnectionId?.contentEquals(expectedOriginal) != true) {
                throw KQuicError.ProtocolViolation("Server original_destination_connection_id does not match")
            }
        }
        channelManager.applyRemoteParameters(parameters)
    }

    private suspend fun handleOpenChannel(channelConfig: KQuicChannelConfig) {
        ensureApplicationReady()
        channelManager.open(channelConfig)
    }

    private fun handleSend(command: QuicConnectionCore.Effect.SendRequested) {
        ensureApplicationReady()
        channelManager.send(command.label, command.payload, command.text, command.reservedBytes)
    }

    private fun handleCloseChannel(label: String) {
        channelManager.close(label)
    }

    private suspend fun handleTick(now: Long) {
        if (closing) return
        packetSpaces.values.forEach { packetSpace ->
            val tracker = packetSpace.ackTracker
            if (tracker.hasPendingAck && now >= tracker.ackDeadlineMs) {
                tracker.frame(now, LOCAL_ACK_DELAY_EXPONENT)?.let {
                    enqueue(packetSpace.encryptionLevel, it, required = false)
                }
            }
        }
        packetSpaces.values.forEach { packetSpace ->
            val recoverySpace = packetSpace.recovery
            if (recoverySpace.lossDeadlineMs(now) <= now) {
                val probes = recoverySpace.ptoProbeFrames()
                if (probes.isEmpty()) {
                    enqueue(
                        packetSpace.encryptionLevel,
                        QuicFrame.Ping,
                        required = false,
                        bypassCongestion = true
                    )
                } else {
                    probes.forEach {
                        enqueue(
                            packetSpace.encryptionLevel,
                            it,
                            required = false,
                            bypassCongestion = true
                        )
                    }
                }
                recoverySpace.onPtoExpired()
            }
        }
        core.command(QuicConnectionCore.Command.Deadline(now))
            .forEach { applyCoreEffect(it) }
        channelManager.drain()
    }

    private suspend fun flush(): Long? {
        var pacingDeadlineMs: Long? = null
        for (level in listOf(
            QuicEncryptionLevel.INITIAL,
            QuicEncryptionLevel.HANDSHAKE,
            QuicEncryptionLevel.ONE_RTT
        )) {
            val packetSpace = packetSpaces[level]
            val queue = packetSpace.frameQueue
            while (queue.isNotEmpty && packetSpace.protection != null && !closing) {
                val queued = queue.removeFirst()
                queuedFrameBytes -= queued.estimatedBytes
                val frame = queued.frame
                val encoded = QuicFrames.encode(frame)
                val recoverySpace = packetSpace.recovery
                val estimatedPacketSize = if (level == QuicEncryptionLevel.INITIAL) {
                    QuicInitialDatagrams.MINIMUM_SIZE
                } else {
                    encoded.size + PACKET_OVERHEAD
                }
                if (frame.isAckEliciting() &&
                    !queued.canSend(recoverySpace, estimatedPacketSize)
                ) {
                    queue.addFirst(queued)
                    queuedFrameBytes += queued.estimatedBytes
                    break
                }
                val now = nowMs()
                if (level == QuicEncryptionLevel.ONE_RTT && frame.isAckEliciting() &&
                    !queued.bypassCongestion) {
                    val pacingDelay = pacer.delayMs(
                        now, estimatedPacketSize, recoverySpace.congestionWindow,
                        recoverySpace.smoothedRttMs
                    )
                    if (pacingDelay > 0) {
                        queue.addFirst(queued)
                        queuedFrameBytes += queued.estimatedBytes
                        pacingDeadlineMs = now + pacingDelay
                        break
                    }
                }
                val packetNumber = packetSpace.nextPacketNumber
                packetSpace.nextPacketNumber = packetNumber + 1uL
                val keys = checkNotNull(packetSpace.protection).write
                val packet = when (level) {
                    QuicEncryptionLevel.INITIAL -> packetProtection.sealLong(
                        type = QuicLongPacketType.INITIAL,
                        destinationConnectionId = checkNotNull(remoteConnectionId ?: originalDestinationConnectionId),
                        sourceConnectionId = localConnectionId,
                        packetNumber = packetNumber,
                        packetNumberLength = PACKET_NUMBER_LENGTH,
                        plaintext = encoded,
                        keys = keys
                    )
                    QuicEncryptionLevel.HANDSHAKE -> packetProtection.sealLong(
                        type = QuicLongPacketType.HANDSHAKE,
                        destinationConnectionId = checkNotNull(remoteConnectionId),
                        sourceConnectionId = localConnectionId,
                        packetNumber = packetNumber,
                        packetNumberLength = PACKET_NUMBER_LENGTH,
                        plaintext = encoded,
                        keys = keys,
                        padInitialDatagram = false
                    )
                    QuicEncryptionLevel.ONE_RTT -> packetProtection.sealShort(
                        destinationConnectionId = checkNotNull(remoteConnectionId),
                        packetNumber = packetNumber,
                        packetNumberLength = PACKET_NUMBER_LENGTH,
                        keyPhase = false,
                        plaintext = encoded,
                        keys = keys
                    )
                }
                val outgoing = checkNotNull(core.transmit(remoteAddress, packet, now))
                applyCoreEffect(outgoing)
                if (level == QuicEncryptionLevel.ONE_RTT && frame.isAckEliciting() &&
                    !queued.bypassCongestion) {
                    pacer.onPacketSent(
                        outgoing.sentAtMs, packet.size, recoverySpace.congestionWindow,
                        recoverySpace.smoothedRttMs
                    )
                }
                recoverySpace.onPacketSent(
                    number = packetNumber,
                    sentAtMs = outgoing.sentAtMs,
                    bytes = packet.size,
                    ackEliciting = frame.isAckEliciting(),
                    retransmittableFrames = if (frame.isRetransmittable()) {
                        listOf(frame)
                    } else {
                        emptyList()
                    }
                )
                if (frame == QuicFrame.HandshakeDone &&
                    endpointRole == QuicEndpointRole.SERVER &&
                    handshake.complete
                ) {
                    handshakeConfirmed = true
                    completeConnection()
                    discardKeys(QuicEncryptionLevel.INITIAL)
                    discardKeys(QuicEncryptionLevel.HANDSHAKE)
                    packetSpaces[QuicPacketNumberSpace.INITIAL].discardRecovery()
                    packetSpaces[QuicPacketNumberSpace.HANDSHAKE].discardRecovery()
                }
            }
        }
        return pacingDeadlineMs
    }

    private fun scheduleNextTick(pacingDeadlineMs: Long?) {
        val deadlines = buildList {
            core.nextDeadlineMs()?.let(::add)
            pacingDeadlineMs?.let(::add)
            packetSpaces.values.forEach { packetSpace ->
                packetSpace.ackTracker.takeIf { it.hasPendingAck }?.ackDeadlineMs?.let(::add)
                packetSpace.recovery.lossDeadlineMs(nowMs()).takeUnless {
                    it == Long.MAX_VALUE }?.let(::add)
            }
        }
        deadlines.minOrNull()?.let { ioDriver.scheduleTick(it - nowMs()) }
    }

    private fun enqueue(
        level: QuicEncryptionLevel,
        frame: QuicFrame,
        required: Boolean,
        bypassCongestion: Boolean = !frame.isAckEliciting()
    ): Boolean {
        val estimated = QuicFrames.encode(frame).size + PACKET_OVERHEAD
        if (!bypassCongestion &&
            queuedFrameBytes + estimated > config.limits.maxSendQueueBytes
        ) {
            if (required) {
                throw KQuicError.TransportFailure("KQuic send queue exceeded its byte limit")
            }
            return false
        }
        packetSpaces[level].frameQueue.addLast(
            QuicQueuedFrame(frame, estimated, bypassCongestion)
        )
        queuedFrameBytes += estimated
        return true
    }

    private fun validateDestinationConnectionId(invariant: QuicInvariantHeader) {
        val destination = invariant.destinationConnectionId
        val accepted = destination.contentEquals(localConnectionId) ||
            (
                endpointRole == QuicEndpointRole.SERVER &&
                    invariant is QuicInvariantHeader.Long &&
                    invariant.firstByte ushr 4 and 0x03 == 0 &&
                    destination.contentEquals(originalDestinationConnectionId)
                )
        if (!accepted) {
            throw KQuicError.ProtocolViolation("Packet used an unknown destination connection ID")
        }
    }

    private fun QuicPacketProtectionKeys.clear() {
        key.fill(0)
        iv.fill(0)
        headerProtectionKey.fill(0)
    }

    private fun discardKeys(level: QuicEncryptionLevel) {
        packetSpaces[level].protection?.let { pair ->
            pair.read.clear()
            pair.write.clear()
        }
        packetSpaces[level].protection = null
    }

    private fun completeConnection() {
        if (!handshakeConfirmed || !handshake.complete || connected.isCompleted) return
        val wireStats = core.stats()
        logger.info(
            "QUIC handshake confirmed packetsSent=${wireStats.packetsSent} " +
                "packetsReceived=${wireStats.packetsReceived}"
        )
        connected.complete(
            handshake.remoteIdentity ?: throw KQuicError.AuthenticationFailed("TLS completed without a remote identity")
        )
    }

    private fun ensureApplicationReady() {
        if (!handshakeConfirmed || !connected.isCompleted) {
            throw KQuicError.TransportUnavailable()
        }
    }

    private suspend fun gracefulClose(reason: String) {
        if (closing) return
        if (packetSpaces[QuicEncryptionLevel.ONE_RTT].protection != null) {
            enqueue(QuicEncryptionLevel.ONE_RTT, QuicFrame.ConnectionClose(0uL, 0uL, reason), required = false)
            flush()
        }
        core.close(reason)
        if (!connected.isCompleted) {
            connected.completeExceptionally(KQuicError.TransportFailure(reason))
        }
        emitCoreEvent(KQuicTransportEvent.Disconnected(reason))
    }

    private fun currentStats(): KQuicTransportStats {
        val spaces = packetSpaces.values.map(QuicPacketSpaceState::recovery)
        val wire = core.stats()
        return KQuicTransportStats(
            bytesSent = wire.bytesSent,
            bytesReceived = wire.bytesReceived,
            packetsSent = wire.packetsSent,
            packetsReceived = wire.packetsReceived,
            packetsLost = spaces.sumOf(QuicRecovery::packetsLost),
            datagramsDropped = channelManager.datagramsDropped,
            minimumRttMs = spaces.mapNotNull(QuicRecovery::minimumRttMs).minOrNull(),
            smoothedRttMs = spaces.mapNotNull(QuicRecovery::smoothedRttMs).minOrNull(),
            congestionWindowBytes = spaces.sumOf(QuicRecovery::congestionWindow),
            bytesInFlight = spaces.sumOf(QuicRecovery::bytesInFlight),
            ptoCount = spaces.sumOf { it.ptoCount.toLong() },
            streamsBlocked = channelManager.streamsBlocked
        )
    }

    private fun nowMs(): Long = platformRuntime.clock.nowNanos() / 1_000_000L

    private fun QuicFrame.isAckEliciting(): Boolean =
        this !is QuicFrame.Ack && this !is QuicFrame.ConnectionClose

    private fun QuicFrame.isRetransmittable(): Boolean =
        this !is QuicFrame.Ack &&
            this !is QuicFrame.Datagram &&
            this !is QuicFrame.ConnectionClose

}

private const val CONNECTION_ID_LENGTH = 8
private const val PACKET_NUMBER_LENGTH = 2
private const val PACKET_OVERHEAD = 64
private const val NETWORK_CHECK_INTERVAL_MS = 1_000L
private const val MAX_FRAME_PAYLOAD = 1_150
private const val LOCAL_ACK_DELAY_EXPONENT = 3
