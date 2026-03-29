package com.kerobit.kpeer

/**
 * Policy for emitting *local* ICE candidates to the application (e.g. for batching before
 * sending over your signaling transport).
 *
 * Remote ICE buffering (until the remote description is set) is handled internally by the
 * transport layer and is not configured here.
 */
data class KIceCandidateEmitPolicy(
    /**
     * Flush cadence for outgoing ICE batching (ms).
     * Set to `0` to effectively emit candidates immediately (no batching).
     */
    val flushInterval: Long = 50L,
    /**
     * Max candidates per batch. When null, batches are only bounded by [flushInterval].
     */
    val maxBatchSize: Int? = null,
) {
    init {
        require(flushInterval >= 0L) { "flushInterval must be >= 0" }
        require(maxBatchSize == null || maxBatchSize > 0) { "maxBatchSize must be null or > 0" }
    }
}
