package com.kerobit.kpeer

/** Simple cancellable handle returned by callback-based subscriptions. */
fun interface KSubscription {
    fun cancel()
}
