package com.templatesProvider

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
import java.util.Base64
import kotlin.random.Random

/**
 * UTILITIES FOR ANICHIN COPY
 * Berisi fungsi-fungsi pembantu yang diperlukan oleh Ultimate Scraper Template.
 */

suspend fun rateLimitDelay() {
    try { delay(100L + Random.nextLong(400L)) } catch (_: Exception) {}
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
            if (attempt < maxRetries - 1) delay(initialDelay * (attempt + 1))
        }
    }
    throw lastException ?: Exception("Max retries reached")
}

fun String.safeCleanBloat(original: String, regex: Regex): String {
    return try {
        val cleaned = regex.replace(this, "").trim()
        cleaned.ifBlank { original }
    } catch (_: Exception) { original }
}

fun String?.safeExtractYear(): Int? {
    if (this == null) return null
    return try { Regex("\\d{4}").find(this)?.value?.toIntOrNull() } catch (_: Exception) { null }
}

fun String?.safeExtractEpNum(): Int? {
    if (this == null) return null
    return try {
        Regex("""(?i)(?:episode|ep)\s*(\d+(?:\.\d+)?)""").find(this)
            ?.groupValues?.get(1)
            ?.toDoubleOrNull()?.toInt()
    } catch (_: Exception) { null }
}

fun String.safeHttpsify(): String {
    return try { if (startsWith("//")) "https:$this" else this } catch (_: Exception) { this }
}

fun String?.safeIsBase64(): Boolean {
    if (this.isNullOrBlank()) return false
    return try { Base64.getDecoder().decode(this); true } catch (_: Exception) { false }
}

fun String.safeDecode(): String {
    return try { String(Base64.getDecoder().decode(this)) } catch (_: Exception) { this }
}

fun String?.safeGetQuality(): Int {
    if (this == null) return Qualities.Unknown.value
    return try {
        val q = this.lowercase()
        when {
            q.contains("2160") || q.contains("4k") -> Qualities.P2160.value
            q.contains("1080") || q.contains("fhd") -> Qualities.P1080.value
            q.contains("720") || q.contains("hd") -> Qualities.P720.value
            q.contains("480") || q.contains("sd") -> Qualities.P480.value
            else -> Qualities.Unknown.value
        }
    } catch (_: Exception) { Qualities.Unknown.value }
}

fun logDebug(tag: String, message: String) = Log.d(tag, message)
fun logError(tag: String, message: String, error: Throwable? = null) {
    Log.e(tag, message)
    error?.let { Log.e(tag, "Cause: ${it.message}") }
}
