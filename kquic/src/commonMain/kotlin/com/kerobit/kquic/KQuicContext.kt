package com.kerobit.kquic

import com.kerobit.kquic.internal.CommonKQuicTransportFactory
import com.kerobit.kquic.internal.KQuicPlatformServices
import com.kerobit.kquic.internal.createKQuicPlatformServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Resources used by a [KQuicPeer]. */
class KQuicContext private constructor(
    val scope: CoroutineScope,
    val platformContext: Any?,
    internal val transportFactory: KQuicTransportFactory,
    internal val platformServices: KQuicPlatformServices,
    private val ownsScope: Boolean
) {
    constructor(
        scope: CoroutineScope? = null,
        platformContext: Any? = null
    ) : this(defaultResources(scope, platformContext), platformContext)

    internal constructor(
        scope: CoroutineScope?,
        platformContext: Any?,
        transportFactory: KQuicTransportFactory,
        platformServices: KQuicPlatformServices = createKQuicPlatformServices(platformContext)
    ) : this(
        scope = scope ?: createKQuicScope(),
        platformContext = platformContext,
        transportFactory = transportFactory,
        platformServices = platformServices,
        ownsScope = scope == null
    )

    private constructor(
        resources: DefaultKQuicResources,
        platformContext: Any?
    ) : this(
        scope = resources.scope,
        platformContext = platformContext,
        transportFactory = resources.transportFactory,
        platformServices = resources.platformServices,
        ownsScope = resources.ownsScope
    )

    fun dispose() {
        if (ownsScope) scope.cancel()
    }
}

private data class DefaultKQuicResources(
    val scope: CoroutineScope,
    val transportFactory: KQuicTransportFactory,
    val platformServices: KQuicPlatformServices,
    val ownsScope: Boolean
)

private fun defaultResources(
    requestedScope: CoroutineScope?,
    platformContext: Any?
): DefaultKQuicResources {
    val scope = requestedScope ?: createKQuicScope()
    val services = createKQuicPlatformServices(platformContext)
    return DefaultKQuicResources(
        scope = scope,
        transportFactory = CommonKQuicTransportFactory(scope, services),
        platformServices = services,
        ownsScope = requestedScope == null
    )
}

private fun createKQuicScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + Dispatchers.Default)
