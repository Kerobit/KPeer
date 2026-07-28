package com.kerobit.kquic

import com.kerobit.kquic.internal.KQuicPlatformRuntime
import com.kerobit.kquic.internal.extractSubjectPublicKeyInfo
import com.kerobit.kquic.internal.toHex
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Owns rendezvous state and wire messages. It never selects a candidate pair or starts a QUIC
 * connection; those decisions belong to [KQuicConnection].
 */
internal class KQuicSignaler(
    private val config: KQuicConfig,
    private val platformRuntime: KQuicPlatformRuntime,
    private val gatherCandidates: suspend () -> List<KQuicCandidate>,
    private val logger: KQuicLogger
) {
    private val mutableSignals = MutableSharedFlow<KQuicSignal>(
        replay = 64,
        extraBufferCapacity = 64
    )
    val signals: Flow<KQuicSignal> = mutableSignals.asSharedFlow()

    private val identityReady = CompletableDeferred<KQuicIdentity>()
    private val gatheringReady = CompletableDeferred<Unit>()
    private val mutableLocalCandidates = linkedSetOf<KQuicCandidate>()
    private val mutableRemoteCandidates = linkedSetOf<KQuicCandidate>()

    val localCandidates: Set<KQuicCandidate> get() = mutableLocalCandidates
    val remoteCandidates: Set<KQuicCandidate> get() = mutableRemoteCandidates
    var localCredentials: KQuicConnectivityCredentials? = null
        private set
    var remoteCredentials: KQuicConnectivityCredentials? = null
        private set
    var localGatheringComplete: Boolean = false
        private set
    var remoteGatheringComplete: Boolean = false
        private set

    private var localIdentityPublicKeySha256: String? = null
    private var localIdentity: KQuicIdentity? = null
    private var remoteIdentityPublicKeySha256: String? = null
    private var localSignalSent = false

    suspend fun start() {
        localCredentials = createConnectivityCredentials()
        val identity = when (val security = config.security) {
            is KQuicSecurity.Explicit -> security.identityProvider.identityFor(
                KQuicHandshakeRequest(config.rendezvousId, config.initiator)
            )
            KQuicSecurity.EphemeralAuthenticatedSignaling ->
                platformRuntime.crypto.ephemeralIdentityProvider.identityFor(
                    KQuicHandshakeRequest(config.rendezvousId, config.initiator)
                )
        }
        localIdentity = identity
        localIdentityPublicKeySha256 = identity.cachedPublicKeySha256
            ?: platformRuntime.crypto.sha256(
                extractSubjectPublicKeyInfo(identity.certificateChainDer.first())
            ).toHex()
        identityReady.complete(identity)

        gatherCandidates().forEach(mutableLocalCandidates::add)
        localGatheringComplete = true
        logger.info("Local candidate gathering complete: ${mutableLocalCandidates.summary()}")
        logger.debug("Local candidates: ${mutableLocalCandidates.debugSummary()}")
        if (!gatheringReady.isCompleted) gatheringReady.complete(Unit)
        if (config.initiator && !localSignalSent) emitInitialSignal()
    }

    fun failStart(error: Throwable) {
        if (!identityReady.isCompleted) identityReady.completeExceptionally(error)
        if (!gatheringReady.isCompleted) gatheringReady.completeExceptionally(error)
    }

    suspend fun awaitReady() {
        identityReady.await()
        gatheringReady.await()
    }

    suspend fun identity(): KQuicIdentity = identityReady.await()

    fun close() {
        localIdentity?.release()
        localIdentity = null
    }

    suspend fun apply(remote: KQuicSignal) {
        if (remote.rendezvousId != config.rendezvousId) {
            throw KQuicError.InvalidSignal("Signal rendezvousId does not match this peer")
        }
        logger.info("Received ${remote.signalSummary()}")
        logger.debug("Remote candidates in signal: ${remote.candidates().debugSummary()}")
        when (remote) {
            is KQuicOffer -> {
                if (config.initiator) {
                    throw KQuicError.InvalidSignal("Initiator received a KQuicOffer")
                }
                setRemoteIdentityFingerprint(remote.identityPublicKeySha256)
                setRemoteConnectivityCredentials(remote.connectivityCredentials)
                remote.candidates.forEach(::addRemoteCandidate)
                remoteGatheringComplete = remote.endOfCandidates
                mutableSignals.emit(
                    KQuicAnswer(
                        rendezvousId = config.rendezvousId,
                        candidates = mutableLocalCandidates.toList(),
                        identityPublicKeySha256 = checkNotNull(localIdentityPublicKeySha256),
                        connectivityCredentials = checkNotNull(localCredentials),
                        endOfCandidates = localGatheringComplete
                    )
                )
                localSignalSent = true
                if (localGatheringComplete) {
                    mutableSignals.emit(KQuicEndOfCandidates(config.rendezvousId))
                }
            }
            is KQuicAnswer -> {
                if (!config.initiator) {
                    throw KQuicError.InvalidSignal("Responder received a KQuicAnswer")
                }
                setRemoteIdentityFingerprint(remote.identityPublicKeySha256)
                setRemoteConnectivityCredentials(remote.connectivityCredentials)
                remote.candidates.forEach(::addRemoteCandidate)
                remoteGatheringComplete = remote.endOfCandidates
            }
            is KQuicCandidateUpdate -> {
                remote.candidates.forEach(::addRemoteCandidate)
                if (remote.endOfCandidates) remoteGatheringComplete = true
            }
            is KQuicEndOfCandidates -> remoteGatheringComplete = true
        }
    }

    suspend fun verify(remoteIdentity: KQuicRemoteIdentity): KQuicVerificationResult =
        when (val security = config.security) {
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

    private fun emitInitialSignal() {
        mutableSignals.tryEmit(
            KQuicOffer(
                rendezvousId = config.rendezvousId,
                candidates = mutableLocalCandidates.toList(),
                identityPublicKeySha256 = checkNotNull(localIdentityPublicKeySha256),
                connectivityCredentials = checkNotNull(localCredentials),
                endOfCandidates = localGatheringComplete
            )
        )
        logger.info(
            "Emitted offer candidates=${mutableLocalCandidates.size} " +
                "endOfCandidates=$localGatheringComplete"
        )
        localSignalSent = true
    }

    private fun addRemoteCandidate(candidate: KQuicCandidate) {
        if (candidate.type == KQuicCandidateType.RELAY) {
            throw KQuicError.InvalidSignal("Relay candidates are not supported by this prototype")
        }
        mutableRemoteCandidates += candidate
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
            usernameFragment = platformRuntime.random.bytes(8).toHex(),
            password = platformRuntime.random.bytes(24).toHex()
        )
}

private fun KQuicSignal.signalSummary(): String = when (this) {
    is KQuicOffer -> "offer candidates=${candidates.size} endOfCandidates=$endOfCandidates"
    is KQuicAnswer -> "answer candidates=${candidates.size} endOfCandidates=$endOfCandidates"
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
