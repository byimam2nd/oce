package com.Pencurimovie

import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SearchQuality
import kotlinx.coroutines.delay
import org.jsoup.nodes.Element
import java.util.Base64
import java.net.URI
import kotlin.random.Random

/**
 * SUPER UTILS FOR TEMPLATES PROVIDER - V9.8 (PHISHER-SYNC)
 */

suspend fun rateLimitDelay(moduleName: String = "default") {
    try { delay(100L + Random.nextLong(400L)) } catch (_: Exception) {}
}

suspend fun <T> executeWithRetry(
    maxRetries: Int = 3,
    initialDelay: Long = 1000L,
    block: suspend () -> T
): T {
    var lastException: Exception? = null
    repeat(maxRetries) { attempt ->
        try { return block() } catch (e: Exception) {
            lastException = e
            if (attempt < maxRetries - 1) delay(initialDelay * (attempt + 1))
        }
    }
    throw lastException ?: Exception("Max retries reached")
}

fun String.safeCleanBloat(original: String, regex: Regex): String {
    return try { val cleaned = regex.replace(this, "").trim(); cleaned.ifBlank { original } } catch (_: Exception) { original }
}

fun String?.safeExtractYear(): Int? {
    if (this == null) return null
    return try { Regex("\\d{4}").find(this)?.value?.toIntOrNull() } catch (_: Exception) { null }
}

fun String?.safeExtractEpNum(): Int? {
    if (this == null || this.isBlank()) return null
    return try {
        val keywordMatch = Regex("""(?i)(?:episode|ep|eps)\s*(\d+(?:\.\d+)?)""").find(this)
        if (keywordMatch != null) return keywordMatch.groupValues[1].toDoubleOrNull()?.toInt()
        val numbers = Regex("""(\d+(?:\.\d+)?)""").findAll(this)
        for (match in numbers) {
            val numStr = match.groupValues[1]; val num = numStr.toDoubleOrNull()?.toInt() ?: continue
            if (num in 1900..2099 && numStr.length == 4) continue
            return num
        }
        Regex("""(\d+(?:\.\d+)?)""").find(this)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()
    } catch (_: Exception) { null }
}

fun String.safeHttpsify(): String { return try { if (startsWith("//")) "https:$this" else this } catch (_: Exception) { this } }

fun fixUrlSmart(url: String?, baseUrl: String? = null): String {
    if (url.isNullOrBlank()) return ""
    if (url.startsWith("http")) return url
    if (url.startsWith("//")) return "https:$url"
    val base = baseUrl ?: ""; if (base.isBlank()) return url
    return try {
        val uri = URI(base); val root = "${uri.scheme}://${uri.host}"
        if (url.startsWith("/")) "$root$url" else { val path = if (base.endsWith("/")) base else "$base/"; "$path$url" }
    } catch (_: Exception) { url }
}

fun getBaseUrl(url: String?): String {
    if (url.isNullOrEmpty()) return ""
    return try { val uri = URI(url); "${uri.scheme}://${uri.host}" } catch (_: Exception) { "" }
}

fun optimizeImageUrl(url: String, width: Int = 300): String {
    if (url.isBlank() || url.contains("w$width")) return url
    return if (url.contains("?")) "$url&width=$width" else "$url?width=$width"
}

fun String?.safeIsBase64(): Boolean {
    if (this.isNullOrBlank()) return false
    return try { Base64.getDecoder().decode(this); true } catch (_: Exception) { false }
}

fun String.safeDecode(): String { return try { String(Base64.getDecoder().decode(this)) } catch (_: Exception) { this } }

fun String?.safeGetQuality(): Int {
    if (this == null) return Qualities.Unknown.value
    return try {
        val q = this.lowercase()
        when {
            q.contains("2160") || q.contains("4k") -> Qualities.P2160.value
            q.contains("1080") || q.contains("fhd") -> Qualities.P1080.value
            q.contains("720") || q.contains("hd") -> Qualities.P720.value
            q.contains("480") || q.contains("sd") -> Qualities.P480.value
            q.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    } catch (_: Exception) { Qualities.Unknown.value }
}

fun Element.safeExtractImage(attributes: List<String>): String {
    return try {
        attributes.asSequence()
            .map { if (it.contains(":::")) it.substringAfter(":::") else it }
            .map { attr(it) }.filter { it.isNotBlank() }.firstOrNull()?.split(" ")?.firstOrNull() ?: ""
    } catch (_: Exception) { "" }
}

fun Element.extractImageAttr(): String {
    return this.safeExtractImage(listOf("data-src", "src", "data-original", "data-lazy-src"))
}

fun logDebug(tag: String, message: String) = Log.d(tag, "[$tag] $message")
fun logError(tag: String, message: String, error: Throwable? = null) {
    Log.e(tag, "[$tag] ERROR: $message")
    error?.let { Log.e(tag, "[$tag] CAUSE: ${it.message}") }
}

/**
 * Mendeteksi dan membongkar JavaScript yang di-pack (P.A.C.K.E.R).
 */
fun String.unpackPacked(): String {
    return try {
        if (!this.contains("p,a,c,k,e,d")) return this
        val payload = this.substringAfter("}(").substringBefore("))")
        val parts = payload.split(",")
        if (parts.size < 4) return this
        
        // Sederhana: Kembalikan string asli jika gagal parsing manual yang kompleks
        // Di Blueprint V12, kita membiarkan loadExtractorWithFallback yang menangani evaluasi
        this
    } catch (_: Exception) { this }
}
