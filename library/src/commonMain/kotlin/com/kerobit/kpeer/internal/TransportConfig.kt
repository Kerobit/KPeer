package com.kerobit.kpeer.internal

import com.kerobit.kpeer.IceServer

internal data class TransportConfig(
    val iceServers: List<IceServer>,
    val controlChannelLabel: String,
    val dataChannelLabel: String,
    val ordered: Boolean,
    val reliable: Boolean
)
