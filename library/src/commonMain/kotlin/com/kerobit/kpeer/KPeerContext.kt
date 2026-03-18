package com.kerobit.kpeer

import kotlinx.coroutines.CoroutineScope

/**
 * Minimal context for KPeer transport (replaces SDK KeroContext).
 * Provides a coroutine scope and optional platform context for WebRTC init (e.g. Android Context).
 */
public data class KPeerContext(
    public val scope: CoroutineScope,
    public val platformContext: Any? = null
)
