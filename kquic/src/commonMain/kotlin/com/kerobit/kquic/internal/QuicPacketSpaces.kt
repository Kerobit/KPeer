package com.kerobit.kquic.internal

internal data class QuicProtectionPair(val read: QuicPacketProtectionKeys, val write: QuicPacketProtectionKeys)

internal data class QuicQueuedFrame(val frame: QuicFrame, val estimatedBytes: Int,
                                    val bypassCongestion: Boolean = false) {
    fun canSend(recovery: QuicRecovery, packetBytes: Int): Boolean =
        bypassCongestion || recovery.canSend(packetBytes)
}

internal class QuicFrameSendQueue {
    private val priority = ArrayDeque<QuicQueuedFrame>()
    private val control = ArrayDeque<QuicQueuedFrame>()
    private val application = mutableMapOf<ULong, ArrayDeque<QuicQueuedFrame>>()
    private val applicationOrder = ArrayDeque<ULong>()
    val isNotEmpty: Boolean get() = priority.isNotEmpty() || control.isNotEmpty() || applicationOrder.isNotEmpty()

    fun addLast(frame: QuicQueuedFrame) {
        when {
            frame.bypassCongestion -> priority.addLast(frame)
            frame.frame.applicationQueueKey() == null -> control.addLast(frame)
            else -> addApplication(frame, first = false)
        }
    }

    fun addFirst(frame: QuicQueuedFrame) {
        when {
            frame.bypassCongestion -> priority.addFirst(frame)
            frame.frame.applicationQueueKey() == null -> control.addFirst(frame)
            else -> addApplication(frame, first = true)
        }
    }

    fun removeFirst(): QuicQueuedFrame {
        if (priority.isNotEmpty()) return priority.removeFirst()
        if (control.isNotEmpty()) return control.removeFirst()
        val key = applicationOrder.removeFirst()
        val frames = checkNotNull(application[key])
        val frame = frames.removeFirst()
        if (frames.isEmpty()) application.remove(key) else applicationOrder.addLast(key)
        return frame
    }

    private fun addApplication(frame: QuicQueuedFrame, first: Boolean) {
        val key = checkNotNull(frame.frame.applicationQueueKey())
        val frames = application.getOrPut(key) {
            if (first) applicationOrder.addFirst(key) else applicationOrder.addLast(key)
            ArrayDeque()
        }
        if (first) {
            frames.addFirst(frame)
            applicationOrder.remove(key)
            applicationOrder.addFirst(key)
        } else frames.addLast(frame)
    }
}

private fun QuicFrame.applicationQueueKey(): ULong? = when (this) {
    is QuicFrame.Stream -> streamId
    is QuicFrame.Datagram -> ULong.MAX_VALUE
    else -> null
}

/** All mutable state that belongs to one QUIC packet-number space. */
internal class QuicPacketSpaceState internal constructor(val numberSpace: QuicPacketNumberSpace) {
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
