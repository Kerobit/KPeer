package com.kerobit.kpeer.internal.nativeP2P

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

suspend fun <T> Promise<T>.await(): T = suspendCoroutine { cont ->
    then(
        onFulfilled = { cont.resume(it) },
        onRejected = { err: Any? ->
            val throwable = (err as? Throwable) ?: Exception(err?.toString() ?: "Promise rejected")
            cont.resumeWithException(throwable)
        }
    )
}
