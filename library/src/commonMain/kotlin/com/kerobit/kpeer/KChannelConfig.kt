package com.kerobit.kpeer

/** Configuration for a single WebRTC data channel. */
public data class KChannelConfig(
    /**
     * Data channel label.
     *
     * This is the identifier used to match an incoming channel to a `KChannel` instance.
     */
    public val label: String,
    /**
     * Whether the data channel guarantees in-order delivery.
     *
     * When `false`, messages may be delivered out of order.
     */
    public val ordered: Boolean = true,
    /**
     * Whether the data channel is reliable.
     *
     * When `false`, the transport may drop messages (best-effort) to reduce latency.
     */
    public val reliable: Boolean = true,
    /**
     * Threshold (in bytes) for the underlying transport "buffered amount low" notification.
     * When set, platforms that support it will configure their native data channel accordingly.
     */
    public val bufferedAmountLowThreshold: Long? = null
)

