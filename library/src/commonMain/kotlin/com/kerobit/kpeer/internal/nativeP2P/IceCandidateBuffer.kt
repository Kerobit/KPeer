package com.kerobit.kpeer.internal.nativeP2P

/**
 * Buffers ICE candidates received before the remote description is applied.
 *
 * Usage pattern:
 * - Call [reset] before starting a new `setRemoteDescription(...)`.
 * - Use [queueOrAdd] in `addIceCandidate(...)`.
 * - After `setRemoteDescription(...)` succeeds, call [markRemoteDescriptionSetAndFlush].
 */
internal class IceCandidateBuffer<T> {
    private val queue = mutableListOf<T>()
    private var remoteDescriptionSet: Boolean = false

    fun reset() {
        remoteDescriptionSet = false
        // A new `setRemoteDescription(...)` attempt means the previous candidates for that attempt
        // might no longer match the active SDP/negotiation state. Discard them.
        queue.clear()
    }

    fun clearQueue() {
        queue.clear()
    }

    fun queueOrAdd(candidate: T, add: (T) -> Unit) {
        if (remoteDescriptionSet) {
            add(candidate)
        } else {
            queue.add(candidate)
        }
    }

    fun markRemoteDescriptionSetAndFlush(add: (T) -> Unit) {
        if (remoteDescriptionSet) return
        remoteDescriptionSet = true
        if (queue.isEmpty()) return
        val toFlush = queue.toList()
        queue.clear()
        toFlush.forEach(add)
    }
}

