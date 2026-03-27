package com.kerobit.kpeer.internal.nativeP2P

import com.kerobit.kpeer.KChannelConfig

internal data class DataChannelControlParams(
    val ordered: Boolean,
    /**
     * When null, the native layer should omit maxRetransmits (meaning: use default reliable behavior).
     * When 0, the native layer should set maxRetransmits=0 to indicate best-effort mode.
     */
    val maxRetransmitsOrNull: Int?
)

internal fun KChannelConfig.toControlParams(): DataChannelControlParams {
    return DataChannelControlParams(
        ordered = this.ordered,
        maxRetransmitsOrNull = if (this.reliable) null else 0
    )
}

