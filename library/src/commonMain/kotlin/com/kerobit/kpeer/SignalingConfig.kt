package com.kerobit.kpeer

/**
 * Configuration of how signaling messages are produced/consumed.
 *
 * This is transport-independent (offer/answer/ice) and focuses on ICE behavior:
 * - batch outgoing ICE candidates to avoid bursts
 * - optionally buffer remote ICE candidates until the remote description is set
 */
public data class SignalingConfig(
    /**
     * Flush cadence for outgoing ICE batching (ms).
     * Set to `0` to effectively emit candidates immediately (no batching).
     */
    public val flushInterval: Long = 50L,
    /**
     * Max candidates per batch. When null, batches are only bounded by `flushInterval`.
     */
    public val maxBatchSize: Int? = null,
    /**
     * When true, remote ICE candidates received before applying the remote description
     * are queued and flushed right after `setRemoteDescription(...)`.
     */
    public val bufferRemoteIceUntilDescription: Boolean = true,
) {
    init {
        require(flushInterval >= 0L) { "flushInterval must be >= 0" }
        require(maxBatchSize == null || maxBatchSize > 0) { "maxBatchSize must be null or > 0" }
    }
}

