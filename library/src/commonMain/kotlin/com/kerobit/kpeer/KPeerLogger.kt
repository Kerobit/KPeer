package com.kerobit.kpeer

/**
 * Simple logger interface for transport layer. No-op by default.
 */
public interface KPeerLogger {
    public fun debug(message: String)
    public fun info(message: String)
    public fun warn(message: String)
}

/**
 * No-op logger. Use when logging is not required.
 */
public object NoOpKPeerLogger : KPeerLogger {
    override fun debug(message: String) {}
    override fun info(message: String) {}
    override fun warn(message: String) {}
}
