package com.kerobit.kpeer

/**
 * Simple logger interface for transport layer. No-op by default.
 */
interface KPeerLogger {
    fun debug(message: String)
    fun info(message: String)
    fun warn(message: String)
}

/**
 * No-op logger. Use when logging is not required.
 */
object NoOpKPeerLogger : KPeerLogger {
    override fun debug(message: String) {}
    override fun info(message: String) {}
    override fun warn(message: String) {}
}
