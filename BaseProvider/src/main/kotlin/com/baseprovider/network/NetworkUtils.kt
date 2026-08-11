package com.baseprovider.network

import com.lagradost.api.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.random.Random

// Hard 4xx: retry tidak akan pernah sukses. 403 TIDAK disertakan lagi —
// Cloudflare challenge (403) diserahkan ke fallback luar (rotasi UA / host mirror).
internal val NON_RETRYABLE_HTTP = Regex("""\b(404|410|451)\b""")

// Indikasi Cloudflare / anti-bot challenge yang tidak membaik dengan retry biasa.
internal val CLOUDFLARE_HTTP = Regex(
    """\b403\b|Just a moment|__cf_chl|cf-chl-|challenge-platform|cf-ray|cloudflare""",
    RegexOption.IGNORE_CASE
)

// Rate limit: layak di-retry dengan backoff lebih panjang.
internal val RATE_LIMIT_HTTP = Regex("""\b429\b""")

suspend fun <T> executeWithRetry(
    maxRetries: Int = 3,
    initialDelay: Long = 1000L,
    maxDelay: Long = 10_000L,
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    repeat(maxRetries) { attempt ->
        try { return block() } catch (e: Exception) {
            // cancellation murni (user cancel) harus diteruskan, bukan timeout
            if (e is CancellationException && e !is TimeoutCancellationException) throw e
            val msg = e.message ?: ""
            if (NON_RETRYABLE_HTTP.containsMatchIn(msg)) throw e
            if (CLOUDFLARE_HTTP.containsMatchIn(msg)) throw e
            lastException = e
            if (attempt < maxRetries - 1) {
                val rateLimited = RATE_LIMIT_HTTP.containsMatchIn(msg)
                val base = if (rateLimited) initialDelay * 2 else initialDelay
                val delayMs = minOf(base * (1L shl attempt) + Random.nextLong(500L), maxDelay)
                Log.d("OCE", "executeWithRetry attempt ${attempt + 1}/$maxRetries failed: ${e.message}, retry in ${delayMs}ms")
                delay(delayMs)
            }
        }
    }
    throw lastException ?: Exception("Max retries reached")
}
