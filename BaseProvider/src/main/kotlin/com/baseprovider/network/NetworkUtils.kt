package com.baseprovider.network

import com.lagradost.api.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlin.random.Random
import java.util.Date

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

/**
 * Exception untuk status HTTP non-2xx yang di-detect dari `NiceResponse.code`.
 * `app.get` (NiceHttp) TIDAK melempar exception pada status error, jadi kode
 * pemanggil harus memeriksa `.code` secara eksplisit dan melempar exception ini.
 * Membawa `retryAfterSeconds` yang di-parse dari header `Retry-After` (RFC 7231):
 * bisa bernilai detik maupun HTTP-date.
 */
class HttpStatusException(
    val code: Int,
    val retryAfterSeconds: Long? = null,
    message: String
) : Exception(message) {
    override val message: String
        get() = super.message ?: "HTTP $code"
}

/**
 * Parse header `Retry-After` ke detik. Mendukung format detik (integer)
 * maupun HTTP-date (mis. `Wed, 21 Oct 2015 07:28:00 GMT`). Inspirasi:
 * Scrapling `scrapling/spiders/throttle.py:10-28`.
 */
fun parseRetryAfter(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    value.trim().toLongOrNull()?.let { return it }
    return try {
        val delta = Date.parse(value.trim()) - System.currentTimeMillis()
        if (delta > 0) delta / 1000 else 0L
    } catch (_: Exception) {
        null
    }
}

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
            if (e is HttpStatusException) {
                when {
                    e.code == 429 -> {
                        // Rate limit: hormati Retry-After kalau ada (Scrapling throttle.py),
                        // fallback ke backoff eksponensial yang lebih agresif.
                        lastException = e
                        if (attempt < maxRetries - 1) {
                            val retryMs = e.retryAfterSeconds
                                ?.takeIf { it > 0 }
                                ?.let { it * 1000L }
                            val delayMs = minOf(
                                retryMs
                                    ?: (initialDelay * 2 * (1L shl attempt) + Random.nextLong(500L)),
                                maxDelay
                            )
                            Log.d("OCE", "executeWithRetry attempt ${attempt + 1}/$maxRetries rate-limited (429), retry in ${delayMs}ms")
                            delay(delayMs)
                        }
                    }
                    NON_RETRYABLE_HTTP.containsMatchIn(e.message.orEmpty()) -> throw e
                    CLOUDFLARE_HTTP.containsMatchIn(e.message.orEmpty()) -> throw e
                    else -> {
                        lastException = e
                        if (attempt < maxRetries - 1) {
                            val delayMs = minOf(initialDelay * (1L shl attempt) + Random.nextLong(500L), maxDelay)
                            Log.d("OCE", "executeWithRetry attempt ${attempt + 1}/$maxRetries failed: ${e.message}, retry in ${delayMs}ms")
                            delay(delayMs)
                        }
                    }
                }
            } else {
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
    }
    throw lastException ?: Exception("Max retries reached")
}
