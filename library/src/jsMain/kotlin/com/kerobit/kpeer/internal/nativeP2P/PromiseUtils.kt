package com.kerobit.kpeer.internal.nativeP2P

import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import kotlin.js.Promise

suspend fun <T> Promise<T>.await(): T = suspendCoroutine { cont ->
    then(
        onFulfilled = { cont.resume(it) },
        onRejected = { err -> cont.resumeWithException(if (err is Throwable) err else Exception(err.toString())) }
    )
}
