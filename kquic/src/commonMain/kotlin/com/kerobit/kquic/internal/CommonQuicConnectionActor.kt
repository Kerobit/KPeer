package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicChannelConfig
import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicDelivery
import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicIdentity
import com.kerobit.kquic.KQuicRemoteIdentity
import com.kerobit.kquic.KQuicTransportEvent
import com.kerobit.kquic.KQuicTransportRole
import com.kerobit.kquic.KQuicTransportStats
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Serial QUIC v1 connection actor. UDP jobs only enqueue commands; all protocol state is owned by
 * [runActor], which prevents packet numbers, recovery, TLS and stream state from racing.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class CommonQuicConnectionActor(
    parentScope: CoroutineScope,
    private val socket: KQuicDatagramSocket,
    private val remoteAddress: KQuicSocketAddress,
    private val role: KQuicTransportRole,
    private val identity: KQuicIdentity,
    private val config: KQuicConfig,
    private val platformServices: KQuicPlatformServices,
    private val emitEvent: suspend (KQuicTransportEvent) -> Unit
) {
    private sealed interface Command {
        data class Inbound(val datagram: KQuicInboundDatagram) : Command
        data class OpenChannel(val config: KQuicChannelConfig) : Command
        data class Send(
            val label: String,
            val payload: ByteArray,
            val text: Boolean,
            val reservedBytes: Int
        ) : Command
        data class CloseChannel(val label: String) : Command
        data class Fatal(val error: Throwable) : Command
        data object Tick : Command
        data object Close : Command
    }

    private data class ProtectionPair(
        val read: QuicPacketProtectionKeys,
        val write: QuicPacketProtectionKeys
    )

    private data class QueuedFrame(
        val frame: QuicFrame,
        val estimatedBytes: Int
    )

    private class ChannelBinding(
        val id: ULong,
        val label: String,
        val delivery: KQuicDelivery,
        var streamId: ULong
    )

    private data class PendingStreamWrite(
        val binding: ChannelBinding,
        val bytes: ByteArray,
        val fin: Boolean,
        val reservedBytes: Int,
        var position: Int = 0
    )

    private val scopeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + scopeJob)
    private val commands = Channel<Command>(
        capacity = config.limits.maxBufferedPackets.coerceIn(16, 1_024)
    )
    private val connected = CompletableDeferred<KQuicRemoteIdentity>()
    private val crypto = requireNotNull(platformServices.cryptoPrimitives)
    private val handshakeCrypto = requireNotNull(platformServices.handshakeCrypto)
    private val packetProtection = QuicPacketProtection(crypto)
    private val keySchedule = Tls13KeySchedule(handshakeCrypto.primitives)
    private val endpointRole = if (role == KQuicTransportRole.CLIENT) {
        QuicEndpointRole.CLIENT
    } else {
        QuicEndpointRole.SERVER
    }
    private val localConnectionId = platformServices.secureRandom(CONNECTION_ID_LENGTH)
    private var originalDestinationConnectionId: ByteArray? =
        if (endpointRole == QuicEndpointRole.CLIENT) {
            platformServices.secureRandom(CONNECTION_ID_LENGTH)
        } else {
            null
        }
    private var remoteConnectionId: ByteArray? = null
    private var tls: CommonQuicTlsSession? = null
    private var remoteIdentity: KQuicRemoteIdentity? = null
    private var remoteParameters: QuicTransportParameterValues? = null
    private var tlsComplete = false
    private var handshakeConfirmed = false
    private var started = false
    private var closing = false
    private var lastActivityMs = nowMs()
    private var lastStatsAtMs = 0L
    private var lastNetworkCheckAtMs = lastActivityMs
    private val initialHostAddresses = platformServices.hostAddresses().sorted()

    private val protectionByLevel = mutableMapOf<QuicEncryptionLevel, ProtectionPair>()
    private val nextPacketNumber = QuicPacketNumberSpace.entries
        .associateWith { 0uL }
        .toMutableMap()
    private val largestReceived = QuicPacketNumberSpace.entries
        .associateWith<QuicPacketNumberSpace, ULong?> { null }
        .toMutableMap()
    private val ackTrackers = QuicPacketNumberSpace.entries
        .associateWith { QuicAckTracker() }
    private val recovery = QuicRecoverySpaces()
    private val frameQueues = QuicEncryptionLevel.entries
        .associateWith { ArrayDeque<QueuedFrame>() }
    private val cryptoReceive = mapOf(
        QuicEncryptionLevel.INITIAL to QuicOffsetReassembler(
            config.limits.maxBufferedCryptoBytes,
            config.limits.maxBufferedCryptoBytes.toULong()
        ),
        QuicEncryptionLevel.HANDSHAKE to QuicOffsetReassembler(
            config.limits.maxBufferedCryptoBytes,
            config.limits.maxBufferedCryptoBytes.toULong()
        )
    )
    private val cryptoSendOffset = mutableMapOf(
        QuicEncryptionLevel.INITIAL to 0uL,
        QuicEncryptionLevel.HANDSHAKE to 0uL
    )
    private var queuedFrameBytes = 0
    private val pendingApplicationBytes = AtomicInt(0)

    private val streamManager = QuicStreamManager(
        role = endpointRole,
        localMaxBidirectionalStreams = config.limits.maxBidirectionalStreams.toULong(),
        localMaxUnidirectionalStreams = config.limits.maxUnidirectionalStreams.toULong(),
        localMaxStreamData = config.limits.maxStreamData.toULong(),
        maximumBufferedBytesPerStream = config.limits.maxBufferedStreamBytes
    )
    private val streamSenders = mutableMapOf<ULong, QuicStreamSender>()
    private val streamDecoders = mutableMapOf<ULong, QuicChannelFrameDecoder>()
    private val pendingStreamWrites = mutableListOf<PendingStreamWrite>()
    private val streamDataConsumed = mutableMapOf<ULong, ULong>()
    private val channelsById = mutableMapOf<ULong, ChannelBinding>()
    private val channelsByLabel = mutableMapOf<String, ChannelBinding>()
    private var nextChannelId = if (endpointRole == QuicEndpointRole.CLIENT) 0uL else 1uL
    private var localControlStreamId: ULong? = null
    private var peerMaxConnectionData = 0uL
    private var peerConnectionDataSent = 0uL
    private var localConnectionDataConsumed = 0uL

    private var bytesSent = 0L
    private var bytesReceived = 0L
    private var packetsSent = 0L
    private var packetsReceived = 0L
    private var datagramsDropped = 0L
    private var streamsBlocked = 0L

    suspend fun connect(): KQuicRemoteIdentity {
        if (!started) start()
        return connected.await()
    }

    fun openChannel(config: KQuicChannelConfig): Boolean {
        if (config.delivery == KQuicDelivery.UNRELIABLE &&
            remoteParameters?.maxDatagramFrameSize == null
        ) {
            throw KQuicError.UnsupportedDelivery(
                "The remote peer did not negotiate QUIC DATAGRAM"
            )
        }
        return commands.trySend(Command.OpenChannel(config)).isSuccess
    }

    fun send(label: String, payload: ByteArray, text: Boolean): Boolean {
        if (payload.size > config.limits.maxMessageSize) return false
        val reservation = payload.size + APPLICATION_MESSAGE_OVERHEAD
        while (true) {
            val current = pendingApplicationBytes.load()
            if (current + reservation > config.limits.maxSendQueueBytes) return false
            if (pendingApplicationBytes.compareAndSet(current, current + reservation)) break
        }
        return commands.trySend(
            Command.Send(label, payload.copyOf(), text, reservation)
        ).isSuccess.also { accepted ->
            if (!accepted) pendingApplicationBytes.fetchAndAdd(-reservation)
        }
    }

    fun closeChannel(label: String) {
        commands.trySend(Command.CloseChannel(label))
    }

    fun close() {
        if (commands.trySend(Command.Close).isFailure) {
            socket.close()
            scopeJob.cancel()
        }
    }

    private fun start() {
        started = true
        scope.launch { runActor() }
        scope.launch {
            try {
                while (isActive && !closing) {
                    commands.send(Command.Inbound(socket.receive()))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                if (!closing) commands.trySend(Command.Fatal(error))
            }
        }
        scope.launch {
            while (isActive && !closing) {
                delay(TICK_INTERVAL_MS)
                commands.send(Command.Tick)
            }
        }
        if (endpointRole == QuicEndpointRole.CLIENT) {
            commands.trySend(Command.Tick)
        }
    }

    private suspend fun runActor() {
        try {
            if (endpointRole == QuicEndpointRole.CLIENT) initializeClient()
            for (command in commands) {
                when (command) {
                    is Command.Inbound -> handleInbound(command.datagram)
                    is Command.OpenChannel -> handleOpenChannel(command.config)
                    is Command.Send -> handleSend(command)
                    is Command.CloseChannel -> handleCloseChannel(command.label)
                    is Command.Fatal -> throw command.error
                    Command.Tick -> handleTick()
                    Command.Close -> {
                        gracefulClose("application_close")
                        return
                    }
                }
                flush()
                if (closing) return
            }
        } catch (error: CancellationException) {
            if (!closing) throw error
        } catch (error: Throwable) {
            val mapped = error as? KQuicError
                ?: KQuicError.TransportFailure(error.message ?: "QUIC actor failed")
            if (!connected.isCompleted) connected.completeExceptionally(mapped)
            emitEvent(KQuicTransportEvent.Failed(mapped))
        } finally {
            closing = true
            tls?.close()
            protectionByLevel.values.forEach { pair ->
                pair.read.clear()
                pair.write.clear()
            }
            protectionByLevel.clear()
            pendingApplicationBytes.store(0)
            identity.release()
            socket.close()
            commands.close()
            scopeJob.cancel()
        }
    }

    private suspend fun initializeClient() {
        val destinationId = checkNotNull(originalDestinationConnectionId)
        installInitialKeys(destinationId)
        initializeTls()
        handleTlsEvents(checkNotNull(tls).start())
        flush()
    }

    private fun initializeServer(invariant: QuicInvariantHeader.Long) {
        originalDestinationConnectionId = invariant.destinationConnectionId
        remoteConnectionId = invariant.sourceConnectionId
        installInitialKeys(invariant.destinationConnectionId)
        initializeTls()
    }

    private fun initializeTls() {
        val parameters = QuicTransportParameterValues(
            originalDestinationConnectionId =
                if (endpointRole == QuicEndpointRole.SERVER) {
                    originalDestinationConnectionId
                } else {
                    null
                },
            maxIdleTimeoutMs = config.idleTimeoutMs.toULong(),
            maxUdpPayloadSize = MAX_UDP_DATAGRAM_SIZE.toULong(),
            initialMaxData = config.limits.maxConnectionData.toULong(),
            initialMaxStreamDataBidiLocal = config.limits.maxStreamData.toULong(),
            initialMaxStreamDataBidiRemote = config.limits.maxStreamData.toULong(),
            initialMaxStreamDataUni = config.limits.maxStreamData.toULong(),
            initialMaxStreamsBidi = config.limits.maxBidirectionalStreams.toULong(),
            initialMaxStreamsUni = config.limits.maxUnidirectionalStreams.toULong(),
            initialSourceConnectionId = localConnectionId,
            maxDatagramFrameSize = MAX_FRAME_PAYLOAD.toULong()
        )
        tls = CommonQuicTlsSession(endpointRole, identity, parameters, handshakeCrypto)
    }

    private fun installInitialKeys(destinationId: ByteArray) {
        val initial = QuicInitialCrypto(crypto)
        protectionByLevel[QuicEncryptionLevel.INITIAL] = if (
            endpointRole == QuicEndpointRole.CLIENT
        ) {
            ProtectionPair(
                read = initial.derive(destinationId, QuicInitialRole.SERVER),
                write = initial.derive(destinationId, QuicInitialRole.CLIENT)
            )
        } else {
            ProtectionPair(
                read = initial.derive(destinationId, QuicInitialRole.CLIENT),
                write = initial.derive(destinationId, QuicInitialRole.SERVER)
            )
        }
    }

    private suspend fun handleInbound(datagram: KQuicInboundDatagram) {
        if (closing || StunProtocol.isStun(datagram.payload)) return
        if (datagram.source.port != remoteAddress.port) return
        lastActivityMs = nowMs()
        bytesReceived += datagram.payload.size
        packetsReceived++

        val invariant = QuicInvariantHeaders.decode(
            datagram.payload,
            shortHeaderDestinationIdLength = localConnectionId.size
        )
        if (endpointRole == QuicEndpointRole.SERVER && tls == null) {
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
        val keys = protectionByLevel[level]?.read ?: return
        val space = level.packetNumberSpace
        val opened = when (invariant) {
            is QuicInvariantHeader.Long -> packetProtection.openLong(
                datagram.payload,
                largestReceived[space],
                keys
            )
            is QuicInvariantHeader.Short -> packetProtection.openShort(
                datagram.payload,
                localConnectionId.size,
                largestReceived[space],
                keys
            )
        }
        val frames = QuicFrames.decodeAll(opened.plaintext)
        val ackEliciting = frames.any { it.isAckEliciting() }
        val now = nowMs()
        val isNew = checkNotNull(ackTrackers[space]).onPacketReceived(
            opened.packetNumber,
            now,
            ackEliciting
        )
        if (!isNew) return
        largestReceived[space] = maxOf(largestReceived[space] ?: 0uL, opened.packetNumber)
        frames.forEach { processFrame(level, it) }
        val tracker = checkNotNull(ackTrackers[space])
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
                val reassembler = cryptoReceive[level]
                    ?: throw KQuicError.ProtocolViolation("CRYPTO at invalid level")
                val contiguous = reassembler.add(frame.offset, frame.data)
                if (contiguous.isNotEmpty()) {
                    handleTlsEvents(checkNotNull(tls).receive(level, contiguous))
                }
            }
            is QuicFrame.Stream -> processStream(frame)
            is QuicFrame.Datagram -> processDatagram(frame)
            is QuicFrame.MaxStreams ->
                streamManager.updatePeerMaxStreams(frame.bidirectional, frame.maximumStreams)
            is QuicFrame.MaxStreamData ->
                streamSenders[frame.streamId]?.let {
                    it.updateMaximumData(frame.maximumStreamData)
                    drainStreamWrites()
                }
            is QuicFrame.MaxData -> {
                peerMaxConnectionData = maxOf(peerMaxConnectionData, frame.maximumData)
                drainStreamWrites()
            }
            is QuicFrame.ResetStream -> {
                streamManager.reset(frame)
                channelsByLabel.values
                    .firstOrNull { it.streamId == frame.streamId }
                    ?.let { emitEvent(KQuicTransportEvent.ChannelClosed(it.label)) }
            }
            is QuicFrame.StopSending -> {
                channelsByLabel.values
                    .firstOrNull { it.streamId == frame.streamId }
                    ?.let { emitEvent(KQuicTransportEvent.ChannelClosed(it.label)) }
            }
            is QuicFrame.ConnectionClose -> {
                emitEvent(KQuicTransportEvent.Disconnected(frame.reason))
                closing = true
                scopeJob.cancel()
            }
            QuicFrame.HandshakeDone -> {
                if (endpointRole != QuicEndpointRole.CLIENT || !tlsComplete) {
                    throw KQuicError.ProtocolViolation("Unexpected HANDSHAKE_DONE")
                }
                handshakeConfirmed = true
                completeConnection()
                discardKeys(QuicEncryptionLevel.INITIAL)
                discardKeys(QuicEncryptionLevel.HANDSHAKE)
                recovery.discard(QuicPacketNumberSpace.INITIAL)
                recovery.discard(QuicPacketNumberSpace.HANDSHAKE)
            }
            is QuicFrame.NewConnectionId,
            is QuicFrame.RetireConnectionId -> Unit
            is QuicFrame.DataBlocked -> enqueue(
                QuicEncryptionLevel.ONE_RTT,
                QuicFrame.MaxData(
                    config.limits.maxConnectionData.toULong() + localConnectionDataConsumed
                ),
                required = false
            )
            is QuicFrame.StreamDataBlocked -> enqueue(
                QuicEncryptionLevel.ONE_RTT,
                QuicFrame.MaxStreamData(
                    frame.streamId,
                    config.limits.maxStreamData.toULong() + localConnectionDataConsumed
                ),
                required = false
            )
            is QuicFrame.StreamsBlocked -> streamsBlocked++
            QuicFrame.Ping -> Unit
        }
    }

    private fun processAck(level: QuicEncryptionLevel, frame: QuicFrame.Ack) {
        val space = level.packetNumberSpace
        val exponent = remoteParameters?.ackDelayExponent?.toInt() ?: LOCAL_ACK_DELAY_EXPONENT
        val delayMs = ((frame.ackDelay shl exponent) / 1_000uL).toLong()
        val result = recovery[space].onAckReceivedDetailed(frame.ranges, nowMs(), delayMs)
        result.framesToRetransmit.forEach {
            enqueue(level, it, required = false)
        }
        drainStreamWrites()
    }

    private suspend fun handleTlsEvents(events: List<QuicTlsEvent>) {
        events.forEach { event ->
            when (event) {
                is QuicTlsEvent.CryptoData -> enqueueCrypto(event.level, event.bytes)
                is QuicTlsEvent.SecretsAvailable -> {
                    protectionByLevel[event.level] = ProtectionPair(
                        read = keySchedule.quicPacketProtectionKeys(event.secrets.read),
                        write = keySchedule.quicPacketProtectionKeys(event.secrets.write)
                    )
                }
                is QuicTlsEvent.RemoteIdentityAvailable -> remoteIdentity = event.identity
                is QuicTlsEvent.TransportParametersAvailable ->
                    applyRemoteParameters(event.parameters)
                QuicTlsEvent.HandshakeComplete -> {
                    tlsComplete = true
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
            throw KQuicError.ProtocolViolation(
                "Remote initial_source_connection_id does not match packet headers"
            )
        }
        if (endpointRole == QuicEndpointRole.CLIENT) {
            val expectedOriginal = checkNotNull(originalDestinationConnectionId)
            if (parameters.originalDestinationConnectionId?.contentEquals(expectedOriginal) != true) {
                throw KQuicError.ProtocolViolation(
                    "Server original_destination_connection_id does not match"
                )
            }
        }
        remoteParameters = parameters
        peerMaxConnectionData = parameters.initialMaxData
        streamManager.updatePeerMaxStreams(true, parameters.initialMaxStreamsBidi)
        streamManager.updatePeerMaxStreams(false, parameters.initialMaxStreamsUni)
    }

    private fun enqueueCrypto(level: QuicEncryptionLevel, bytes: ByteArray) {
        var position = 0
        var offset = checkNotNull(cryptoSendOffset[level])
        while (position < bytes.size) {
            val end = minOf(position + MAX_FRAME_PAYLOAD, bytes.size)
            val chunk = bytes.copyOfRange(position, end)
            enqueue(level, QuicFrame.Crypto(offset, chunk), required = true)
            offset += chunk.size.toULong()
            position = end
        }
        cryptoSendOffset[level] = offset
    }

    private suspend fun handleOpenChannel(channelConfig: KQuicChannelConfig) {
        ensureApplicationReady()
        channelsByLabel[channelConfig.label]?.let { existing ->
            if (existing.delivery != channelConfig.delivery) {
                throw KQuicError.InvalidChannel(
                    "Channel ${channelConfig.label} already uses ${existing.delivery}"
                )
            }
            return
        }
        if (channelConfig.delivery == KQuicDelivery.UNRELIABLE &&
            remoteParameters?.maxDatagramFrameSize == null
        ) {
            throw KQuicError.UnsupportedDelivery(
                "The remote peer did not negotiate QUIC DATAGRAM"
            )
        }
        val streamId = if (channelConfig.delivery == KQuicDelivery.UNRELIABLE) {
            localControlStreamId ?: streamManager.openBidirectional().also {
                localControlStreamId = it
            }
        } else {
            streamManager.openBidirectional()
        }
        val binding = ChannelBinding(
            id = nextChannelId.also { nextChannelId += 2uL },
            label = channelConfig.label,
            delivery = channelConfig.delivery,
            streamId = streamId
        )
        channelsById[binding.id] = binding
        channelsByLabel[binding.label] = binding
        queueStreamMessage(
            binding,
            QuicChannelMessage.Open(binding.id, binding.label, binding.delivery)
        )
    }

    private fun handleSend(command: Command.Send) {
        ensureApplicationReady()
        val binding = channelsByLabel[command.label]
            ?: throw KQuicError.InvalidChannel("Unknown channel ${command.label}")
        val message = if (command.text) {
            QuicChannelMessage.Text(binding.id, command.payload.decodeToString())
        } else {
            QuicChannelMessage.Bytes(binding.id, command.payload)
        }
        if (binding.delivery == KQuicDelivery.RELIABLE_ORDERED) {
            queueStreamMessage(binding, message, reservedBytes = command.reservedBytes)
        } else {
            val encoded = QuicChannelFrames.encode(message, config.limits)
            val remoteMaximum = remoteParameters?.maxDatagramFrameSize?.toInt()
                ?: throw KQuicError.UnsupportedDelivery("QUIC DATAGRAM is unavailable")
            if (encoded.size > minOf(remoteMaximum, MAX_FRAME_PAYLOAD)) {
                datagramsDropped++
                pendingApplicationBytes.fetchAndAdd(-command.reservedBytes)
                return
            }
            if (!enqueue(
                    QuicEncryptionLevel.ONE_RTT,
                    QuicFrame.Datagram(encoded),
                    required = false
                )
            ) {
                datagramsDropped++
            }
            pendingApplicationBytes.fetchAndAdd(-command.reservedBytes)
        }
    }

    private fun handleCloseChannel(label: String) {
        val binding = channelsByLabel.remove(label) ?: return
        channelsById.remove(binding.id)
        queueStreamMessage(
            binding,
            QuicChannelMessage.Close(binding.id),
            fin = binding.delivery == KQuicDelivery.RELIABLE_ORDERED
        )
    }

    private fun queueStreamMessage(
        binding: ChannelBinding,
        message: QuicChannelMessage,
        fin: Boolean = false,
        reservedBytes: Int = 0
    ) {
        val encoded = QuicChannelFrames.encode(message, config.limits)
        pendingStreamWrites += PendingStreamWrite(binding, encoded, fin, reservedBytes)
        drainStreamWrites()
    }

    private fun drainStreamWrites() {
        var index = 0
        while (index < pendingStreamWrites.size) {
            val pending = pendingStreamWrites[index]
            if (pendingStreamWrites.take(index).any {
                    it.binding.streamId == pending.binding.streamId
                }
            ) {
                index++
                continue
            }
            val sender = streamSenders.getOrPut(pending.binding.streamId) {
                QuicStreamSender(
                    pending.binding.streamId,
                    initialPeerStreamCredit(pending.binding.streamId)
                )
            }
            val remaining = pending.bytes.size - pending.position
            val connectionCredit = peerMaxConnectionData - peerConnectionDataSent
            val available = minOf(
                minOf(remaining.toULong(), sender.availableData),
                minOf(connectionCredit, MAX_FRAME_PAYLOAD.toULong())
            ).toInt()
            val emptyFin = remaining == 0 && pending.fin
            if (available == 0 && !emptyFin) {
                streamsBlocked++
                if (connectionCredit == 0uL) {
                    enqueue(
                        QuicEncryptionLevel.ONE_RTT,
                        QuicFrame.DataBlocked(peerMaxConnectionData),
                        required = false
                    )
                }
                if (sender.availableData == 0uL) {
                    enqueue(
                        QuicEncryptionLevel.ONE_RTT,
                        QuicFrame.StreamDataBlocked(
                            pending.binding.streamId,
                            sender.maximumDataLimit
                        ),
                        required = false
                    )
                }
                index++
                continue
            }
            if (queuedFrameBytes + available + PACKET_OVERHEAD * 2 >
                config.limits.maxSendQueueBytes
            ) {
                return
            }
            val end = pending.position + available
            val chunk = pending.bytes.copyOfRange(pending.position, end)
            val final = pending.fin && end == pending.bytes.size
            val frame = sender.frame(chunk, final)
            check(enqueue(QuicEncryptionLevel.ONE_RTT, frame, required = true))
            peerConnectionDataSent += chunk.size.toULong()
            pending.position = end
            if (pending.position == pending.bytes.size) {
                pendingStreamWrites.removeAt(index)
                if (pending.reservedBytes > 0) {
                    pendingApplicationBytes.fetchAndAdd(-pending.reservedBytes)
                }
            } else {
                index++
            }
        }
    }

    private suspend fun processStream(frame: QuicFrame.Stream) {
        val received = streamManager.receive(frame)
        if (received.bytes.isEmpty()) return
        localConnectionDataConsumed += received.bytes.size.toULong()
        val consumedOnStream = checkNotNull(
            streamDataConsumed[frame.streamId] ?: 0uL
        ) + received.bytes.size.toULong()
        streamDataConsumed[frame.streamId] = consumedOnStream
        val decoder = streamDecoders.getOrPut(frame.streamId) {
            QuicChannelFrameDecoder(config.limits)
        }
        decoder.feed(received.bytes).forEach { processChannelMessage(frame.streamId, it) }
        enqueue(
            QuicEncryptionLevel.ONE_RTT,
            QuicFrame.MaxData(
                config.limits.maxConnectionData.toULong() + localConnectionDataConsumed
            ),
            required = false
        )
        enqueue(
            QuicEncryptionLevel.ONE_RTT,
            QuicFrame.MaxStreamData(
                frame.streamId,
                config.limits.maxStreamData.toULong() + consumedOnStream
            ),
            required = false
        )
        streamManager.updateLocalStreamLimit(
            frame.streamId,
            config.limits.maxStreamData.toULong() + consumedOnStream
        )
    }

    private suspend fun processDatagram(frame: QuicFrame.Datagram) {
        val messages = QuicChannelFrameDecoder(config.limits).feed(frame.data)
        if (messages.size != 1 || messages.single() is QuicChannelMessage.Open) {
            throw KQuicError.ProtocolViolation("Invalid KQuic DATAGRAM payload")
        }
        processChannelMessage(NO_STREAM_ID, messages.single())
    }

    private suspend fun processChannelMessage(streamId: ULong, message: QuicChannelMessage) {
        when (message) {
            is QuicChannelMessage.Open -> {
                val existing = channelsByLabel[message.label]
                if (existing != null) {
                    if (existing.delivery != message.delivery) {
                        throw KQuicError.ProtocolViolation(
                            "Channel ${message.label} reopened with another delivery mode"
                        )
                    }
                    channelsById[message.channelId] = existing
                    return
                }
                val binding = ChannelBinding(
                    id = message.channelId,
                    label = message.label,
                    delivery = message.delivery,
                    streamId = streamId
                )
                channelsById[binding.id] = binding
                channelsByLabel[binding.label] = binding
                emitEvent(
                    KQuicTransportEvent.IncomingChannel(
                        KQuicChannelConfig(binding.label, binding.delivery)
                    )
                )
            }
            is QuicChannelMessage.Bytes -> {
                val binding = channelsById[message.channelId] ?: if (streamId == NO_STREAM_ID) {
                    datagramsDropped++
                    return
                } else {
                    throw KQuicError.ProtocolViolation("Payload for unknown channel")
                }
                emitEvent(KQuicTransportEvent.BytesReceived(binding.label, message.payload))
            }
            is QuicChannelMessage.Text -> {
                val binding = channelsById[message.channelId] ?: if (streamId == NO_STREAM_ID) {
                    datagramsDropped++
                    return
                } else {
                    throw KQuicError.ProtocolViolation("Payload for unknown channel")
                }
                emitEvent(KQuicTransportEvent.TextReceived(binding.label, message.text))
            }
            is QuicChannelMessage.Close -> {
                val binding = channelsById.remove(message.channelId) ?: if (
                    streamId == NO_STREAM_ID
                ) {
                    datagramsDropped++
                    return
                } else {
                    throw KQuicError.ProtocolViolation("Close for unknown channel")
                }
                channelsByLabel.remove(binding.label)
                emitEvent(KQuicTransportEvent.ChannelClosed(binding.label))
            }
        }
    }

    private suspend fun handleTick() {
        if (closing) return
        val now = nowMs()
        ackTrackers.forEach { (space, tracker) ->
            if (tracker.hasPendingAck && now >= tracker.ackDeadlineMs) {
                tracker.frame(now, LOCAL_ACK_DELAY_EXPONENT)?.let {
                    enqueue(space.encryptionLevel, it, required = false)
                }
            }
        }
        QuicPacketNumberSpace.entries.forEach { space ->
            val recoverySpace = recovery[space]
            if (recoverySpace.lossDeadlineMs(now) <= now) {
                val probes = recoverySpace.ptoProbeFrames()
                if (probes.isEmpty()) {
                    enqueue(space.encryptionLevel, QuicFrame.Ping, required = false)
                } else {
                    probes.forEach {
                        enqueue(space.encryptionLevel, it, required = false)
                    }
                }
                recoverySpace.onPtoExpired()
            }
        }
        if (now - lastActivityMs >= config.idleTimeoutMs) {
            throw KQuicError.ConnectionTimedOut(config.idleTimeoutMs)
        }
        if (now - lastNetworkCheckAtMs >= NETWORK_CHECK_INTERVAL_MS) {
            lastNetworkCheckAtMs = now
            if (platformServices.hostAddresses().sorted() != initialHostAddresses) {
                gracefulClose("NETWORK_CHANGED")
                return
            }
        }
        if (now - lastStatsAtMs >= STATS_INTERVAL_MS) emitStats(now)
        drainStreamWrites()
    }

    private suspend fun flush() {
        for (level in listOf(
            QuicEncryptionLevel.INITIAL,
            QuicEncryptionLevel.HANDSHAKE,
            QuicEncryptionLevel.ONE_RTT
        )) {
            val queue = checkNotNull(frameQueues[level])
            while (queue.isNotEmpty() && protectionByLevel[level] != null && !closing) {
                val queued = queue.removeFirst()
                queuedFrameBytes -= queued.estimatedBytes
                val frame = queued.frame
                val encoded = QuicFrames.encode(frame)
                val space = level.packetNumberSpace
                val recoverySpace = recovery[space]
                val estimatedPacketSize = if (level == QuicEncryptionLevel.INITIAL) {
                    QuicInitialDatagrams.MINIMUM_SIZE
                } else {
                    encoded.size + PACKET_OVERHEAD
                }
                if (frame.isAckEliciting() && !recoverySpace.canSend(estimatedPacketSize)) {
                    queue.addFirst(queued)
                    queuedFrameBytes += queued.estimatedBytes
                    break
                }
                val packetNumber = checkNotNull(nextPacketNumber[space])
                nextPacketNumber[space] = packetNumber + 1uL
                val keys = checkNotNull(protectionByLevel[level]).write
                val packet = when (level) {
                    QuicEncryptionLevel.INITIAL -> packetProtection.sealLong(
                        type = QuicLongPacketType.INITIAL,
                        destinationConnectionId = checkNotNull(
                            remoteConnectionId ?: originalDestinationConnectionId
                        ),
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
                socket.send(remoteAddress, packet)
                lastActivityMs = nowMs()
                bytesSent += packet.size
                packetsSent++
                recoverySpace.onPacketSent(
                    number = packetNumber,
                    sentAtMs = lastActivityMs,
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
                    tlsComplete
                ) {
                    handshakeConfirmed = true
                    completeConnection()
                    discardKeys(QuicEncryptionLevel.INITIAL)
                    discardKeys(QuicEncryptionLevel.HANDSHAKE)
                    recovery.discard(QuicPacketNumberSpace.INITIAL)
                    recovery.discard(QuicPacketNumberSpace.HANDSHAKE)
                }
            }
        }
    }

    private fun enqueue(
        level: QuicEncryptionLevel,
        frame: QuicFrame,
        required: Boolean
    ): Boolean {
        val estimated = QuicFrames.encode(frame).size + PACKET_OVERHEAD
        if (queuedFrameBytes + estimated > config.limits.maxSendQueueBytes) {
            if (required) {
                throw KQuicError.TransportFailure("KQuic send queue exceeded its byte limit")
            }
            return false
        }
        checkNotNull(frameQueues[level]).addLast(QueuedFrame(frame, estimated))
        queuedFrameBytes += estimated
        return true
    }

    private fun initialPeerStreamCredit(streamId: ULong): ULong {
        val parameters = remoteParameters ?: return 0uL
        val locallyInitiated = (streamId and 0x01uL) ==
            if (endpointRole == QuicEndpointRole.CLIENT) 0uL else 1uL
        return if (locallyInitiated) {
            parameters.initialMaxStreamDataBidiRemote
        } else {
            parameters.initialMaxStreamDataBidiLocal
        }
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
        protectionByLevel.remove(level)?.let { pair ->
            pair.read.clear()
            pair.write.clear()
        }
    }

    private fun completeConnection() {
        if (!handshakeConfirmed || !tlsComplete || connected.isCompleted) return
        connected.complete(
            remoteIdentity ?: throw KQuicError.AuthenticationFailed(
                "TLS completed without a remote identity"
            )
        )
    }

    private fun ensureApplicationReady() {
        if (!handshakeConfirmed || !connected.isCompleted) {
            throw KQuicError.TransportUnavailable()
        }
    }

    private suspend fun gracefulClose(reason: String) {
        if (closing) return
        if (protectionByLevel[QuicEncryptionLevel.ONE_RTT] != null) {
            enqueue(
                QuicEncryptionLevel.ONE_RTT,
                QuicFrame.ConnectionClose(0uL, 0uL, reason),
                required = false
            )
            flush()
        }
        closing = true
        if (!connected.isCompleted) {
            connected.completeExceptionally(KQuicError.TransportFailure(reason))
        }
        emitEvent(KQuicTransportEvent.Disconnected(reason))
    }

    private suspend fun emitStats(now: Long) {
        lastStatsAtMs = now
        val spaces = QuicPacketNumberSpace.entries.map { recovery[it] }
        emitEvent(
            KQuicTransportEvent.StatsUpdated(
                KQuicTransportStats(
                    bytesSent = bytesSent,
                    bytesReceived = bytesReceived,
                    packetsSent = packetsSent,
                    packetsReceived = packetsReceived,
                    packetsLost = spaces.sumOf(QuicRecovery::packetsLost),
                    datagramsDropped = datagramsDropped,
                    minimumRttMs = spaces.mapNotNull(QuicRecovery::minimumRttMs).minOrNull(),
                    smoothedRttMs = spaces.mapNotNull(QuicRecovery::smoothedRttMs).minOrNull(),
                    congestionWindowBytes = spaces.sumOf(QuicRecovery::congestionWindow),
                    bytesInFlight = spaces.sumOf(QuicRecovery::bytesInFlight),
                    ptoCount = spaces.sumOf { it.ptoCount.toLong() },
                    streamsBlocked = streamsBlocked
                )
            )
        )
    }

    private fun nowMs(): Long = platformServices.monotonicClock.nowNanos() / 1_000_000L

    private fun QuicFrame.isAckEliciting(): Boolean =
        this !is QuicFrame.Ack && this !is QuicFrame.ConnectionClose

    private fun QuicFrame.isRetransmittable(): Boolean =
        this !is QuicFrame.Ack &&
            this !is QuicFrame.Datagram &&
            this !is QuicFrame.ConnectionClose

    private val QuicEncryptionLevel.packetNumberSpace: QuicPacketNumberSpace
        get() = when (this) {
            QuicEncryptionLevel.INITIAL -> QuicPacketNumberSpace.INITIAL
            QuicEncryptionLevel.HANDSHAKE -> QuicPacketNumberSpace.HANDSHAKE
            QuicEncryptionLevel.ONE_RTT -> QuicPacketNumberSpace.APPLICATION_DATA
        }

    private val QuicPacketNumberSpace.encryptionLevel: QuicEncryptionLevel
        get() = when (this) {
            QuicPacketNumberSpace.INITIAL -> QuicEncryptionLevel.INITIAL
            QuicPacketNumberSpace.HANDSHAKE -> QuicEncryptionLevel.HANDSHAKE
            QuicPacketNumberSpace.APPLICATION_DATA -> QuicEncryptionLevel.ONE_RTT
        }
}

private const val CONNECTION_ID_LENGTH = 8
private const val PACKET_NUMBER_LENGTH = 2
private const val PACKET_OVERHEAD = 64
private const val MAX_UDP_DATAGRAM_SIZE = 1_200
private const val NETWORK_CHECK_INTERVAL_MS = 1_000L
private const val MAX_FRAME_PAYLOAD = 1_150
private const val LOCAL_ACK_DELAY_EXPONENT = 3
private const val TICK_INTERVAL_MS = 10L
private const val STATS_INTERVAL_MS = 1_000L
private const val APPLICATION_MESSAGE_OVERHEAD = 32
private val NO_STREAM_ID = ULong.MAX_VALUE
