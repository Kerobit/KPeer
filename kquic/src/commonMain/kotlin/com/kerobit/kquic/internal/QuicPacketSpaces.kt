package com.kerobit.kquic.internal

internal data class QuicProtectionPair(val read: QuicPacketProtectionKeys, val write: QuicPacketProtectionKeys)

internal data class QuicQueuedFrame(
    val frame: QuicFrame,
    val estimatedBytes: Int,
    val bypassCongestion: Boolean = false
) {
    fun canSend(recovery: QuicRecovery, packetBytes: Int): Boolean =
        bypassCongestion || recovery.canSend(packetBytes)
}

internal class QuicFrameSendQueue {
    private val priority = ArrayDeque<QuicQueuedFrame>()
    private val regular = ArrayDeque<QuicQueuedFrame>()
    val isNotEmpty: Boolean get() = priority.isNotEmpty() || regular.isNotEmpty()

    fun addLast(frame: QuicQueuedFrame) {
        if (frame.bypassCongestion) priority.addLast(frame) else regular.addLast(frame)
    }

    fun addFirst(frame: QuicQueuedFrame) {
        if (frame.bypassCongestion) priority.addFirst(frame) else regular.addFirst(frame)
    }

    fun removeFirst(): QuicQueuedFrame =
        if (priority.isNotEmpty()) priority.removeFirst() else regular.removeFirst()
}

/** All mutable state that belongs to one QUIC packet-number space. */
internal class QuicPacketSpaceState internal constructor(
    val numberSpace: QuicPacketNumberSpace
) {
    val encryptionLevel: QuicEncryptionLevel = numberSpace.encryptionLevel
    var protection: QuicProtectionPair? = null
    var nextPacketNumber: ULong = 0uL
    var largestReceived: ULong? = null
    val ackTracker = QuicAckTracker()
    var recovery = QuicRecovery()
        private set
    val frameQueue = QuicFrameSendQueue()

    fun discardRecovery() {
        recovery = QuicRecovery()
    }
}

/**
 * Typed replacement for the parallel maps previously owned by the connection actor.
 */
internal class QuicPacketSpaces {
    val values: List<QuicPacketSpaceState> =
        QuicPacketNumberSpace.entries.map(::QuicPacketSpaceState)

    private val byNumberSpace = values.associateBy(QuicPacketSpaceState::numberSpace)
    private val byEncryptionLevel = values.associateBy(QuicPacketSpaceState::encryptionLevel)

    operator fun get(space: QuicPacketNumberSpace): QuicPacketSpaceState =
        checkNotNull(byNumberSpace[space])

    operator fun get(level: QuicEncryptionLevel): QuicPacketSpaceState =
        checkNotNull(byEncryptionLevel[level])
}

internal val QuicEncryptionLevel.packetNumberSpace: QuicPacketNumberSpace
    get() = when (this) {
        QuicEncryptionLevel.INITIAL -> QuicPacketNumberSpace.INITIAL
        QuicEncryptionLevel.HANDSHAKE -> QuicPacketNumberSpace.HANDSHAKE
        QuicEncryptionLevel.ONE_RTT -> QuicPacketNumberSpace.APPLICATION_DATA
    }

internal val QuicPacketNumberSpace.encryptionLevel: QuicEncryptionLevel
    get() = when (this) {
        QuicPacketNumberSpace.INITIAL -> QuicEncryptionLevel.INITIAL
        QuicPacketNumberSpace.HANDSHAKE -> QuicEncryptionLevel.HANDSHAKE
        QuicPacketNumberSpace.APPLICATION_DATA -> QuicEncryptionLevel.ONE_RTT
    }
