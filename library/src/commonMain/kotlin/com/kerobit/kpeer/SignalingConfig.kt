package com.kerobit.kpeer

/**
 * Configuration of how signaling messages are produced/consumed.
 *
 * This is transport-independent (offer/answer/ice) and focuses on ICE behavior:
 * - batch outgoing ICE candidates to avoid bursts
 * - buffering of remote ICE candidates (before remote description is set) is handled internally by the transport layer
 */
data class SignalingConfig(
    /**
     * Flush cadence for outgoing ICE batching (ms).
     * Set to `0` to effectively emit candidates immediately (no batching).
     */
    val flushInterval: Long = 50L,
    /**
     * Max candidates per batch. When null, batches are only bounded by `flushInterval`.
     */
    val maxBatchSize: Int? = null,
) {
    init {
        require(flushInterval >= 0L) { "flushInterval must be >= 0" }
        require(maxBatchSize == null || maxBatchSize > 0) { "maxBatchSize must be null or > 0" }
    }
}

