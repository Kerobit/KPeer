package com.kerobit.kquic

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Resources used by a [KQuicPeer]. */
class KQuicContext internal constructor(
    scope: CoroutineScope?,
    val platformContext: Any?,
    internal val transportFactory: KQuicTransportFactory
) {
    constructor(
        scope: CoroutineScope? = null,
        platformContext: Any? = null
    ) : this(scope, platformContext, UnavailableKQuicTransportFactory)

    private val ownsScope = scope == null
    val scope: CoroutineScope = scope ?: CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun dispose() {
        if (ownsScope) scope.cancel()
    }
}
