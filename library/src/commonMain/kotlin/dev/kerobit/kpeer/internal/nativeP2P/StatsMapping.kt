package dev.kerobit.kpeer.internal.nativeP2P

import dev.kerobit.kpeer.KPeerStatValue

internal fun toStatValue(v: Any?): KPeerStatValue = when (v) {
    null -> KPeerStatValue.Null
    is String -> KPeerStatValue.Str(v)
    is Boolean -> KPeerStatValue.Bool(v)
    is Number -> KPeerStatValue.Num(v.toDouble())
    else -> KPeerStatValue.Str(v.toString())
}

