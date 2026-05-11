package com.Funmovieslix

import com.lagradost.api.Log
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
import kotlin.random.Random

// ============================================
// REGION 1: CONSTANTS
// ============================================

object AutoUsedConstants {
    const val DEFAULT_TIMEOUT = 10000L
    const val FAST_TIMEOUT = 5000L
}

// ============================================
// REGION 2: UTILITY FUNCTIONS
// ============================================

suspend fun rateLimitDelay(moduleName: String = "default") {
    val waitTime = 100L + Random.nextLong(0, 400L)
    delay(waitTime)
}

suspend fun <T> executeWithRetry(
    maxRetries: Int = 3,
    initialDelay: Long = 1000L,
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    repeat(maxRetries) { attempt ->
        try {
            return block()
        } catch (e: Exception) {
            lastException = e
            if (attempt < maxRetries - 1) {
                delay(initialDelay * (attempt + 1))
            }
        }
    }
    throw lastException ?: Exception("Unknown error")
}

fun logDebug(tag: String, message: String) = Log.d(tag, message)

fun logError(tag: String, message: String, error: Throwable? = null) {
    Log.e(tag, message)
    error?.let { Log.e(tag, "Cause: ${it.message}") }
}

fun Element.extractImageAttr(): String = this.attr("data-src").ifEmpty { this.attr("src") }.ifEmpty { "" }
