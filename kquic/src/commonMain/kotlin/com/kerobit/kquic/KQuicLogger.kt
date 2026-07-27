package com.kerobit.kquic

/**
 * Logger for KQuic lifecycle and connectivity diagnostics.
 *
 * KQuic never logs connectivity credentials, relay tokens, or complete identity
 * fingerprints. Candidate addresses are emitted only at debug level.
 */
interface KQuicLogger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
    fun error(message: String)
}

/** Default logger used when the embedding application does not provide one. */
object NoOpKQuicLogger : KQuicLogger {
    override fun debug(message: String) = Unit
    override fun info(message: String) = Unit
    override fun warn(message: String) = Unit
    override fun error(message: String) = Unit
}
