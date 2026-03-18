package com.kerobit.kpeer.internal

import com.kerobit.kpeer.IceServer

internal data class TransportConfig(
    val iceServers: List<IceServer>
)
