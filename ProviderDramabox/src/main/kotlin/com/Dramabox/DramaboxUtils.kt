package com.Dramabox

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.MainAPI
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
import kotlin.random.Random

// ============================================
// REGION 1: CONSTANTS
// ============================================

object AutoUsedConstants {
    const val DEFAULT_TIMEOUT = 10000L
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

fun base64Decode(str: String): String {
    return try {
        String(java.util.Base64.getDecoder().decode(str))
    } catch (e: Exception) {
        str
    }
}

fun getQualityFromName(name: String?): Int {
    if (name == null) return 480
    return when {
        name.contains("2160") || name.contains("4k") -> 2160
        name.contains("1440") || name.contains("2k") -> 1440
        name.contains("1080") -> 1080
        name.contains("720") -> 720
        name.contains("480") -> 480
        name.contains("360") -> 360
        else -> 480
    }
}
