package com.kerobit.kquic.internal

import com.kerobit.kquic.KQuicCandidate
import com.kerobit.kquic.KQuicCandidatePair
import com.kerobit.kquic.KQuicCandidateType
import com.kerobit.kquic.KQuicConfig
import com.kerobit.kquic.KQuicConnectivityCredentials
import com.kerobit.kquic.KQuicError
import com.kerobit.kquic.KQuicLogger
import com.kerobit.kquic.KQuicStunServer
import com.kerobit.kquic.KQuicTransportRole
import com.kerobit.kquic.NoOpKQuicLogger
import kotlinx.coroutines.withTimeoutOrNull

/** A socket and remote address whose authenticated connectivity check has completed. */
internal data class ValidatedPath(
    val socket: KQuicDatagramSocket,
    val remoteAddress: KQuicSocketAddress
)

/**
 * Owns candidate gathering and connectivity checks. A successful [validate] transfers one socket
 * to the returned [ValidatedPath]; all other sockets stay owned here and are closed.
 */
internal class CandidateTransport(
    private val platformRuntime: KQuicPlatformRuntime,
    private val config: KQuicConfig,
    var diagnosticLogger: KQuicLogger = NoOpKQuicLogger
) {
    private data class BoundCandidate(
        val candidate: KQuicCandidate,
        val socket: KQuicDatagramSocket
    )

    private val candidatesByFoundation = linkedMapOf<String, BoundCandidate>()
    private val ownedSockets = linkedSetOf<KQuicDatagramSocket>()
    private var gathered = false
    private var closed = false

    suspend fun gatherCandidates(): List<KQuicCandidate> {
        check(!closed) { "KQuic transport is closed" }
        if (gathered) return candidatesByFoundation.values.map(BoundCandidate::candidate)
        val socketFactory = platformRuntime.udp
        val addresses = platformRuntime.network.hostAddresses()
            .filterNot { it.startsWith("fe80:", ignoreCase = true) }
            .ifEmpty { listOf("127.0.0.1") }
        diagnosticLogger.info(
            "Gathering UDP candidates hostAddresses=${addresses.size} " +
                "stunServers=${config.stunServers.size}"
        )
        diagnosticLogger.debug("Host addresses: ${addresses.joinToString()}")

        addresses.forEachIndexed { index, address ->
            val socketResult = runCatching {
                socketFactory.bind(KQuicSocketAddress(address, 0))
            }
            val socket = socketResult.getOrNull()
            if (socket == null) {
                diagnosticLogger.warn(
                    "UDP bind failed address=$address reason=" +
                        "${socketResult.exceptionOrNull()?.message ?: "unknown"}"
                )
                return@forEachIndexed
            }
            ownedSockets += socket
            diagnosticLogger.debug("UDP socket bound address=${socket.localAddress}")
            if (config.gatherHostCandidates) {
                register(
                    KQuicCandidate(
                        foundation = "host-$index",
                        type = KQuicCandidateType.HOST,
                        host = address,
                        port = socket.localAddress.port,
                        priority = candidatePriority(KQuicCandidateType.HOST, index)
                    ),
                    socket
                )
            }
        }
        if (ownedSockets.isEmpty()) {
            throw KQuicError.CandidateGatheringFailed("No UDP socket could be bound")
        }

        val stunGatheringCompleted = withTimeoutOrNull(config.candidateGatheringTimeoutMs) {
            ownedSockets.toList().forEachIndexed { socketIndex, socket ->
                config.stunServers.forEachIndexed { serverIndex, server ->
                    discoverServerReflexive(socket, server)?.let { mapped ->
                        val related = socket.localAddress
                        register(
                            KQuicCandidate(
                                foundation = "srflx-$socketIndex-$serverIndex",
                                type = KQuicCandidateType.SERVER_REFLEXIVE,
                                host = mapped.host,
                                port = mapped.port,
                                priority = candidatePriority(
                                    KQuicCandidateType.SERVER_REFLEXIVE,
                                    socketIndex
                                ),
                                relatedHost = related.host,
                                relatedPort = related.port
                            ),
                            socket
                        )
                    }
                }
            }
            true
        } ?: false
        if (!stunGatheringCompleted) {
            diagnosticLogger.warn(
                "STUN gathering timed out after ${config.candidateGatheringTimeoutMs} ms; " +
                    "continuing with ${candidatesByFoundation.size} gathered candidates"
            )
        }
        gathered = true
        if (candidatesByFoundation.isEmpty()) {
            throw KQuicError.CandidateGatheringFailed(
                "Candidate gathering completed without an address to advertise"
            )
        }
        return candidatesByFoundation.values.map(BoundCandidate::candidate)
    }

    suspend fun validate(
        candidatePair: KQuicCandidatePair,
        role: KQuicTransportRole,
        localCredentials: KQuicConnectivityCredentials,
        remoteCredentials: KQuicConnectivityCredentials
    ): ValidatedPath {
        check(!closed) { "KQuic transport is closed" }
        if (!gathered) gatherCandidates()
        if (candidatePair.remote.type == KQuicCandidateType.RELAY) {
            throw KQuicError.ConnectivityCheckFailed("Relay candidates are not supported")
        }
        val local = candidatesByFoundation[candidatePair.local.foundation]
            ?: throw KQuicError.ConnectivityCheckFailed("Unknown local candidate")
        val remoteAddress = KQuicSocketAddress(
            candidatePair.remote.host,
            candidatePair.remote.port
        )
        diagnosticLogger.info(
            "Starting authenticated connectivity check role=$role " +
                "timeoutMs=${config.connectivityCheckTimeoutMs}"
        )
        diagnosticLogger.debug(
            "Connectivity endpoints local=${local.socket.localAddress} remote=$remoteAddress"
        )
        // The authenticated check can reveal a peer-reflexive endpoint when NAT rewrites the
        // address or port. QUIC must use that observed endpoint, not the signaled candidate.
        val validatedRemoteAddress = performConnectivityCheck(
            socket = local.socket,
            remoteAddress = remoteAddress,
            role = role,
            localCredentials = localCredentials,
            remoteCredentials = remoteCredentials,
            priority = candidatePair.local.priority
        )
        diagnosticLogger.info("Authenticated connectivity check succeeded")
        diagnosticLogger.debug(
            "Connectivity endpoint validated signaledRemote=$remoteAddress " +
                "validatedRemote=$validatedRemoteAddress"
        )

        ownedSockets.filter { it !== local.socket }.forEach(KQuicDatagramSocket::close)
        ownedSockets.remove(local.socket)
        return ValidatedPath(local.socket, validatedRemoteAddress)
    }

    fun close() {
        if (closed) return
        closed = true
        ownedSockets.forEach(KQuicDatagramSocket::close)
        ownedSockets.clear()
    }

    private fun register(candidate: KQuicCandidate, socket: KQuicDatagramSocket) {
        candidatesByFoundation[candidate.foundation] = BoundCandidate(candidate, socket)
    }

    private suspend fun discoverServerReflexive(
        socket: KQuicDatagramSocket,
        server: KQuicStunServer
    ): KQuicSocketAddress? {
        val destination = KQuicSocketAddress(server.host, server.port)
        repeat(STUN_MAX_ATTEMPTS) { attempt ->
            diagnosticLogger.debug(
                "STUN binding attempt=${attempt + 1}/$STUN_MAX_ATTEMPTS " +
                    "server=$destination local=${socket.localAddress}"
            )
            val transactionId = platformRuntime.random.bytes(12)
            val request = StunProtocol.bindingDiscoveryRequest(transactionId)
            val sendResult = runCatching { socket.send(destination, request) }
            if (sendResult.isFailure) {
                diagnosticLogger.warn(
                    "STUN send failed server=$destination local=${socket.localAddress} " +
                        "reason=${sendResult.exceptionOrNull()?.message ?: "unknown"}"
                )
                return null
            }
            val response = withTimeoutOrNull(STUN_ATTEMPT_TIMEOUT_MS) {
                while (true) {
                    val inbound = socket.receive()
                    if (!StunProtocol.isStun(inbound.payload)) continue
                    val decoded = runCatching {
                        StunProtocol.decode(inbound.payload)
                    }.getOrNull() ?: continue
                    if (decoded.type == StunProtocol.BINDING_SUCCESS_RESPONSE &&
                        decoded.transactionId.contentEquals(transactionId)
                    ) {
                        return@withTimeoutOrNull StunProtocol.mappedAddress(decoded)
                    }
                }
                null
            }
            if (response != null) {
                diagnosticLogger.info("STUN server-reflexive candidate discovered")
                diagnosticLogger.debug(
                    "STUN mapped local=${socket.localAddress} server=$destination mapped=$response"
                )
                return response
            }
        }
        diagnosticLogger.warn(
            "STUN server did not answer server=$destination local=${socket.localAddress} " +
                "attempts=$STUN_MAX_ATTEMPTS"
        )
        return null
    }

    private suspend fun performConnectivityCheck(
        socket: KQuicDatagramSocket,
        remoteAddress: KQuicSocketAddress,
        role: KQuicTransportRole,
        localCredentials: KQuicConnectivityCredentials,
        remoteCredentials: KQuicConnectivityCredentials,
        priority: Long
    ): KQuicSocketAddress {
        val hmac = platformRuntime.crypto.handshakeCrypto.primitives::hmacSha256
        val controlling = role == KQuicTransportRole.CLIENT
        val tieBreaker = randomULong()
        val expectedUsername =
            "${localCredentials.usernameFragment}:${remoteCredentials.usernameFragment}"
        var attempts = 0
        var inboundStunPackets = 0
        var authenticatedPackets = 0
        val validatedRemoteAddress = withTimeoutOrNull(
            config.connectivityCheckTimeoutMs
        ) connectivityCheck@{
            while (true) {
                attempts++
                val transactionId = platformRuntime.random.bytes(12)
                val request = StunProtocol.connectivityRequest(
                    transactionId = transactionId,
                    local = localCredentials,
                    remote = remoteCredentials,
                    controlling = controlling,
                    useCandidate = controlling,
                    priority = priority,
                    tieBreaker = tieBreaker,
                    hmacSha256 = hmac
                )
                diagnosticLogger.debug(
                    "Connectivity check attempt=$attempts destination=$remoteAddress"
                )
                socket.send(remoteAddress, request)
                val result = withTimeoutOrNull(
                    CONNECTIVITY_ATTEMPT_TIMEOUT_MS
                ) attempt@{
                    while (true) {
                        val inbound = socket.receive()
                        if (!StunProtocol.isStun(inbound.payload)) continue
                        inboundStunPackets++
                        val requestMessage = runCatching {
                            StunProtocol.decode(
                                inbound.payload,
                                localCredentials.password,
                                hmac
                            )
                        }.getOrNull() ?: continue
                        when (requestMessage.type) {
                            StunProtocol.BINDING_REQUEST -> {
                                val username = requestMessage
                                    .attribute(StunProtocol.USERNAME)
                                    ?.decodeToString()
                                if (username != expectedUsername ||
                                    requestMessage.integrityValid != true
                                ) {
                                    continue
                                }
                                authenticatedPackets++
                                socket.send(
                                    inbound.source,
                                    StunProtocol.bindingSuccessResponse(
                                        transactionId = requestMessage.transactionId,
                                        mappedAddress = inbound.source,
                                        integrityPassword = localCredentials.password,
                                        hmacSha256 = hmac
                                    )
                                )
                                if (!controlling &&
                                    requestMessage.attribute(StunProtocol.USE_CANDIDATE) != null
                                ) {
                                    diagnosticLogger.debug(
                                        "Authenticated USE_CANDIDATE received from ${inbound.source}"
                                    )
                                    return@attempt inbound.source
                                }
                            }
                            StunProtocol.BINDING_SUCCESS_RESPONSE -> {
                                val response = runCatching {
                                    StunProtocol.decode(
                                        inbound.payload,
                                        remoteCredentials.password,
                                        hmac
                                    )
                                }.getOrNull() ?: continue
                                if (controlling &&
                                    response.integrityValid == true &&
                                    response.transactionId.contentEquals(transactionId)
                                ) {
                                    authenticatedPackets++
                                    diagnosticLogger.debug(
                                        "Authenticated connectivity response received from " +
                                            "${inbound.source}"
                                    )
                                    return@attempt inbound.source
                                }
                            }
                        }
                    }
                    null
                }
                if (result != null) return@connectivityCheck result
            }
            null
        }
        if (validatedRemoteAddress == null) {
            diagnosticLogger.warn(
                "Authenticated connectivity check timed out attempts=$attempts " +
                    "inboundStunPackets=$inboundStunPackets " +
                    "authenticatedPackets=$authenticatedPackets"
            )
            throw KQuicError.ConnectivityCheckFailed(
                "No candidate pair passed authenticated connectivity checks"
            )
        }
        return validatedRemoteAddress
    }

    private fun randomULong(): ULong {
        val bytes = platformRuntime.random.bytes(8)
        var result = 0uL
        bytes.forEach { result = (result shl 8) or (it.toInt() and 0xff).toULong() }
        return result
    }

    private fun candidatePriority(type: KQuicCandidateType, index: Int): Long {
        val typePreference = when (type) {
            KQuicCandidateType.HOST -> 126
            KQuicCandidateType.SERVER_REFLEXIVE -> 100
            KQuicCandidateType.RELAY -> 0
        }
        val localPreference = (65_535 - index).coerceAtLeast(1)
        return (typePreference.toLong() shl 24) +
            (localPreference.toLong() shl 8) +
            255
    }
}

private const val STUN_MAX_ATTEMPTS = 4
private const val STUN_ATTEMPT_TIMEOUT_MS = 500L
private const val CONNECTIVITY_ATTEMPT_TIMEOUT_MS = 500L
