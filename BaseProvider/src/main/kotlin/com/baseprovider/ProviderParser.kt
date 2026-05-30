package com.baseprovider

import org.jsoup.nodes.Element
import java.net.URI
import java.util.Base64
// ── Cached Regex Patterns ──

private val WHITESPACE_REGEX = Regex("\\s+")
private val DEDUPLICATE_REGEX = Regex("""^(.*?)\s+\1$""", RegexOption.IGNORE_CASE)
private val YEAR_REGEX = Regex("\\d{4}")
private val EPISODE_KEYWORD_REGEX = Regex("""(?i)(?:episode|ep|eps)\s*(\d+(?:\.\d+)?)""")
private val EPISODE_NUMBER_REGEX = Regex("""(\d+(?:\.\d+)?)""")
val JUST_NUMBER_REGEX = Regex("""^\d+(\.\d+)?$""")

fun Element.safeExtractImage(attributes: List<String>): String {
    return try {
        attributes.asSequence()
            .flatMap { it.split(",").map { a -> a.trim() } }
            .map { attr(it) }.filter { it.isNotBlank() && it != "about:blank" }.firstOrNull()?.split(" ")?.firstOrNull() ?: ""
    } catch (_: Exception) { "" }
}

fun String.safeCleanBloat(original: String, regex: Regex): String {
    return try { val cleaned = regex.replace(this, "").trim(); cleaned.ifBlank { original } } catch (_: Exception) { original }
}

fun String.safeDeduplicate(): String {
    if (this.isBlank()) return this
    var s = this.replace(WHITESPACE_REGEX, " ").trim()
    val separators = listOf(" - ", " | ", " : ", " – ", " — ")
    for (sep in separators) {
        if (s.contains(sep)) {
            val parts = s.split(sep)
            if (parts.size == 2 && parts[0].trim().equals(parts[1].trim(), ignoreCase = true)) {
                return parts[0].trim()
            }
        }
    }
    if (s.length >= 6) {
        val mid = s.length / 2
        if (s.length % 2 == 0) {
            val s1 = s.substring(0, mid).trim()
            val s2 = s.substring(mid).trim()
            if (s1.equals(s2, ignoreCase = true)) return s1
        }
        val s1_alt = s.substring(0, mid).trim()
        val s2_alt = s.substring(mid + 1).trim()
        if (s1_alt.equals(s2_alt, ignoreCase = true)) return s1_alt
    }
    val words = s.split(" ").filter { it.isNotBlank() }
    if (words.size >= 2 && words.size % 2 == 0) {
        val half = words.size / 2
        val firstHalf = words.subList(0, half).joinToString(" ")
        val secondHalf = words.subList(half, words.size).joinToString(" ")
        if (firstHalf.equals(secondHalf, ignoreCase = true)) return firstHalf
    }
    val match = DEDUPLICATE_REGEX.find(s)
    if (match != null) return match.groupValues[1].trim()
    return s
}

fun String?.safeExtractYear(): Int? {
    if (this == null) return null
    return try { YEAR_REGEX.find(this)?.value?.toIntOrNull() } catch (_: Exception) { null }
}

fun String?.safeExtractEpNum(): Int? {
    if (this == null || this.isBlank()) return null
    return try {
        val keywordMatch = EPISODE_KEYWORD_REGEX.find(this)
        if (keywordMatch != null) return keywordMatch.groupValues[1].toDoubleOrNull()?.toInt()
        val numbers = EPISODE_NUMBER_REGEX.findAll(this).map { it.groupValues[1] }.filter { it.toDoubleOrNull() != null }
        numbers.firstOrNull { it.length != 4 || it.toIntOrNull() !in 1900..2099 }?.toDoubleOrNull()?.toInt()
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

fun String?.safeIsBase64(): Boolean {
    if (this.isNullOrBlank()) return false
    return try { Base64.getDecoder().decode(this); true } catch (_: Exception) { false }
}

fun String.safeDecode(): String { return try { String(Base64.getDecoder().decode(this)) } catch (_: Exception) { this } }
