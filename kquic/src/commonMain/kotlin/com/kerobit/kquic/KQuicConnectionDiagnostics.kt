package com.kerobit.kquic

internal fun Throwable.toKQuicError(fallback: String): KQuicError = when (this) {
    is KQuicError -> this
    else -> KQuicError.TransportFailure(message ?: fallback)
}

internal val KQuicConfig.quicRole: KQuicTransportRole
    get() = if (initiator) KQuicTransportRole.CLIENT else KQuicTransportRole.SERVER

internal fun KQuicCandidate.summary(): String =
    "${type.name.lowercase()}:$host:$port foundation=$foundation priority=$priority"

internal fun KQuicCandidatePair.summary(): String =
    "local=[${local.summary()}] remote=[${remote.summary()}]"

internal fun KQuicCandidatePair.redactedSummary(): String =
    "local=${local.type.name.lowercase()}/${local.foundation} " +
        "remote=${remote.type.name.lowercase()}/${remote.foundation}"

internal fun KQuicError.kind(): String = this::class.simpleName ?: "KQuicError"
