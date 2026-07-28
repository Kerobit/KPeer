package com.kerobit.kquic.internal

/** Deterministic connection lifecycle and deadline bookkeeping. */
internal class QuicLifecycle(startedAtMs: Long) {
    private enum class Phase { NEW, RUNNING, CLOSING }

    private var phase = Phase.NEW
    var lastActivityMs: Long = startedAtMs
        private set
    var lastNetworkCheckAtMs: Long = startedAtMs
        private set

    val started: Boolean get() = phase != Phase.NEW
    val closing: Boolean get() = phase == Phase.CLOSING

    fun start(): Boolean {
        if (phase != Phase.NEW) return false
        phase = Phase.RUNNING
        return true
    }

    fun activity(nowMs: Long) {
        if (!closing) lastActivityMs = nowMs
    }

    fun shouldIdleTimeout(nowMs: Long, idleTimeoutMs: Long): Boolean =
        nowMs - lastActivityMs >= idleTimeoutMs

    fun shouldCheckNetwork(nowMs: Long, intervalMs: Long): Boolean =
        nowMs - lastNetworkCheckAtMs >= intervalMs

    fun networkChecked(nowMs: Long) {
        lastNetworkCheckAtMs = nowMs
    }

    fun beginClose(): Boolean {
        if (closing) return false
        phase = Phase.CLOSING
        return true
    }
}
