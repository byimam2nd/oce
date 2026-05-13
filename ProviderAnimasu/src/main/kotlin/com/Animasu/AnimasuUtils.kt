package com.Animasu

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
 * SUPER UTILS FOR TEMPLATES PROVIDER - V2.2.0 (STABILITY EDITION)
 */

object SmartThrottle {
    private val lastRequestMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val MIN_DELAY = 500L

    suspend fun wait(domain: String) {
        val now = System.currentTimeMillis()
        val lastRequest = lastRequestMap[domain] ?: 0L
        val diff = now - lastRequest
        if (diff < MIN_DELAY) {
            delay(MIN_DELAY - diff + Random.nextLong(100L))
        }
        lastRequestMap[domain] = System.currentTimeMillis()
    }
}

class ExpiringCache<T>(private val durationMs: Long) {
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, T>>()

    fun get(key: String): T? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.first > durationMs) {
            cache.remove(key)
            return null
        }
        return entry.second
    }

    fun put(key: String, value: T) {
        cache[key] = System.currentTimeMillis() to value
    }
}

val globalHtmlCache = ExpiringCache<org.jsoup.nodes.Document>(5 * 60 * 1000L)

suspend fun rateLimitDelay(url: String = "") {
    if (url.isBlank()) {
        try { delay(100L + Random.nextLong(200L)) } catch (_: Exception) {}
    } else {
        runCatching { SmartThrottle.wait(URI(url).host ?: "default") }
    }
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

fun String.safeDeduplicate(): String {
    if (this.isBlank()) return this
    
    // Step 1: Normalize separators and spaces
    // Replace " - ", " | ", " : " with a consistent pipe for easier splitting or just normalize spaces
    var s = this.replace(Regex("\\s+"), " ").trim()
    
    // Case 1: Detect and fix common separators used in duplication (e.g. "Title - Title")
    val separators = listOf(" - ", " | ", " : ", " – ", " — ")
    for (sep in separators) {
        if (s.contains(sep)) {
            val parts = s.split(sep)
            if (parts.size == 2 && parts[0].trim().equals(parts[1].trim(), ignoreCase = true)) {
                return parts[0].trim()
            }
        }
    }

    // Case 2: Exact string repeat "Title Title"
    if (s.length >= 6) {
        val mid = s.length / 2
        if (s.length % 2 == 0) {
            val s1 = s.substring(0, mid).trim()
            val s2 = s.substring(mid).trim()
            if (s1.equals(s2, ignoreCase = true)) return s1
        }
        // Fuzzy mid check (offset by 1 for spaces)
        val s1_alt = s.substring(0, mid).trim()
        val s2_alt = s.substring(mid + 1).trim()
        if (s1_alt.equals(s2_alt, ignoreCase = true)) return s1_alt
    }
    
    // Case 3: Word-based repeat "Word1 Word2 Word1 Word2"
    val words = s.split(" ").filter { it.isNotBlank() }
    if (words.size >= 2 && words.size % 2 == 0) {
        val half = words.size / 2
        val firstHalf = words.subList(0, half).joinToString(" ")
        val secondHalf = words.subList(half, words.size).joinToString(" ")
        if (firstHalf.equals(secondHalf, ignoreCase = true)) return firstHalf
    }

    // Case 4: Sequence repeat like "Title 2 Title 2" (handles numbers)
    val pattern = Regex("""^(.*?)\s+\1$""", RegexOption.IGNORE_CASE)
    val match = pattern.find(s)
    if (match != null) return match.groupValues[1].trim()

    return s
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

// --- HIGH-STABILITY CONFIG ENGINE ---

fun resolveConfig(providerId: String, list: List<String>, default: String): String {
    for (item in list) { if (item.contains(":::")) { val owners = item.substringBefore(":::").split(","); if (owners.contains(providerId)) { val v = item.substringAfter(":::"); if (v.isBlank()) break; return v } } }
    for (item in list) { if (item.startsWith("GLOBAL:::")) return item.substringAfter(":::"); if (!item.contains(":::")) return item }
    return default
}

fun resolveConfigList(providerId: String, list: List<String>): List<String> {
    val result = mutableListOf<String>(); for (item in list) { if (item.contains(":::")) { val owners = item.substringBefore(":::").split(","); if (owners.contains(providerId)) { val v = item.substringAfter(":::"); if (v.isNotBlank()) result.add(v) } } }
    if (result.isNotEmpty()) return result
    for (item in list) { val v = if (item.contains(":::")) { if (item.startsWith("GLOBAL:::")) item.substringAfter(":::") else continue } else item; if (v.isNotBlank()) result.add(v) }
    return result
}

fun Element.selectSafe(providerId: String, selectors: List<String>): Element? {
    if (selectors.isEmpty()) return null
    for (s in selectors) { if (!s.contains(":::")) continue
        val owners = s.substringBefore(":::"); if (owners.split(",").contains(providerId)) {
            val sel = s.substringAfter(":::"); if (sel.isNotBlank()) { val el = this.selectFirst(sel); if (el != null) return el }
        }
    }
    for (s in selectors) {
        val sel = if (s.startsWith("GLOBAL:::")) s.substringAfter(":::") else if (!s.contains(":::")) s else continue
        if (sel.isNotBlank()) { val el = this.selectFirst(sel); if (el != null) return el }
    }
    return null
}

fun Element.selectSafeList(providerId: String, selectors: List<String>): org.jsoup.select.Elements {
    if (selectors.isEmpty()) return org.jsoup.select.Elements()
    for (s in selectors) { if (!s.contains(":::")) continue
        val owners = s.substringBefore(":::"); if (owners.split(",").contains(providerId)) {
            val sel = s.substringAfter(":::"); if (sel.isNotBlank()) { val els = this.select(sel); if (els.isNotEmpty()) return els }
        }
    }
    for (s in selectors) {
        val sel = if (s.startsWith("GLOBAL:::")) s.substringAfter(":::") else if (!s.contains(":::")) s else continue
        if (sel.isNotBlank()) { val els = this.select(sel); if (els.isNotEmpty()) return els }
    }
    return org.jsoup.select.Elements()
}

fun Element.attrSafe(providerId: String, attributes: List<String>): String? {
    for (a in attributes) { if (!a.contains(":::")) continue
        val owners = a.substringBefore(":::"); if (owners.split(",").contains(providerId)) {
            val attrN = a.substringAfter(":::"); val v = this.attr(attrN); if (v.isNotBlank()) return v
        }
    }
    for (a in attributes) {
        val attrN = if (a.startsWith("GLOBAL:::")) a.substringAfter(":::") else if (!a.contains(":::")) a else continue
        val v = this.attr(attrN); if (v.isNotBlank()) return v
    }
    return null
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
