package com.kerobit.kpeer

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Minimal context for KPeer transport.
 * Provides an optional caller-owned scope and optional platform context for WebRTC init.
 *
 * When no scope is provided, KPeer creates an internal one that can later be disposed safely.
 */
class KPeerContext(
    scope: CoroutineScope? = null,
    val platformContext: Any? = null
) {
    private val ownsScope: Boolean = scope == null

    val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Cancels only the internally created scope, never a scope supplied by the caller. */
    fun dispose() {
        if (ownsScope) {
            scope.cancel()
        }
    }
}
