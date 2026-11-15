package dev.kerobit.kpeer.internal

import dev.kerobit.kpeer.IceServer

internal data class TransportConfig(
    val iceServers: List<IceServer>
)
