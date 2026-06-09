package com.baseprovider.network

import com.lagradost.api.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.random.Random

internal val NON_RETRYABLE_HTTP = Regex("""\b(403|404|410|451)\b""")

suspend fun <T> executeWithRetry(
    maxRetries: Int = 3,
    initialDelay: Long = 1000L,
    maxDelay: Long = 10_000L,
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    repeat(maxRetries) { attempt ->
        try { return block() } catch (e: Exception) {
            if (e is TimeoutCancellationException) throw e
            val msg = e.message ?: ""
            if (NON_RETRYABLE_HTTP.containsMatchIn(msg)) throw e
            lastException = e
            if (attempt < maxRetries - 1) {
                val delayMs = minOf(initialDelay * (1L shl attempt) + Random.nextLong(500L), maxDelay)
                Log.d("OCE", "executeWithRetry attempt ${attempt + 1}/$maxRetries failed: ${e.message}, retry in ${delayMs}ms")
                delay(delayMs)
            }
        }
    }
    throw lastException ?: Exception("Max retries reached")
}
