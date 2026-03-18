package com.kerobit.kpeer

/** Simple cancellable handle returned by callback-based subscriptions. */
public fun interface KSubscription {
    public fun cancel()
}
