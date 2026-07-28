package com.kerobit.kquic

import com.kerobit.kquic.internal.CommonKQuicTransportFactory
import com.kerobit.kquic.internal.KQuicPlatformRuntime
import com.kerobit.kquic.internal.createKQuicPlatformRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Resources used by a [KQuicPeer]. */
class KQuicContext private constructor(
    val scope: CoroutineScope,
    internal val transportFactory: KQuicTransportFactory,
    internal val platformRuntime: KQuicPlatformRuntime,
    private val ownsScope: Boolean
) {
    constructor(scope: CoroutineScope? = null) : this(defaultResources(scope))

    internal constructor(
        scope: CoroutineScope?,
        transportFactory: KQuicTransportFactory,
        platformRuntime: KQuicPlatformRuntime = createKQuicPlatformRuntime()
    ) : this(
        scope = scope ?: createKQuicScope(),
        transportFactory = transportFactory,
        platformRuntime = platformRuntime,
        ownsScope = scope == null
    )

    private constructor(resources: DefaultKQuicResources) : this(
        scope = resources.scope,
        transportFactory = resources.transportFactory,
        platformRuntime = resources.platformRuntime,
        ownsScope = resources.ownsScope
    )

    fun dispose() {
        if (ownsScope) scope.cancel()
    }
}

private data class DefaultKQuicResources(
    val scope: CoroutineScope,
    val transportFactory: KQuicTransportFactory,
    val platformRuntime: KQuicPlatformRuntime,
    val ownsScope: Boolean
)

private fun defaultResources(requestedScope: CoroutineScope?): DefaultKQuicResources {
    val scope = requestedScope ?: createKQuicScope()
    val runtime = createKQuicPlatformRuntime()
    return DefaultKQuicResources(
        scope = scope,
        transportFactory = CommonKQuicTransportFactory(scope, runtime),
        platformRuntime = runtime,
        ownsScope = requestedScope == null
    )
}

private fun createKQuicScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)
