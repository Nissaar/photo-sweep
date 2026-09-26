package io.github.nissaar.photosweep.api

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Runs the call and suspends until the response headers arrive.
 *
 * A blocking `execute()` inside `withContext` keeps going when the coroutine is
 * cancelled: leaving a screen, or cancelling a sign-in, left the request running and
 * its result was thrown away only once it came back. Cancelling here cancels the call.
 *
 * The caller owns the response and has to close it.
 */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                // A response arriving after cancellation has nobody to close it.
                if (continuation.isActive) continuation.resume(response) else response.close()
            }
        },
    )
}
