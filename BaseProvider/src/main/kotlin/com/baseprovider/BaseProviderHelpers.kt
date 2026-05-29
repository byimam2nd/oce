package com.baseprovider

import com.lagradost.api.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * ENGINE HELPERS - V2.2.0 (STABILITY EDITION)
 * 
 * Berisi utilitas inti untuk networking, caching, logging, dan konfigurasi.
 * Tidak mengandung logika parsing HTML (Jsoup).
 */

object HostCircuitBreaker {
    private val failureCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val cooldownUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val MAX_FAILURES = 3
    private const val COOLDOWN_MS = 60_000L

    fun isOpen(host: String): Boolean {
        if (host.isBlank()) return false
        val until = cooldownUntil[host] ?: return false
        if (System.currentTimeMillis() < until) return true
        cooldownUntil.remove(host)
        return false
    }

    fun reportFailure(host: String) {
        if (host.isBlank()) return
        val count = failureCount.merge(host, 1, Int::plus) ?: 1
        if (count >= MAX_FAILURES) {
            cooldownUntil[host] = System.currentTimeMillis() + COOLDOWN_MS
            Log.d("OCE", "Circuit breaker OPEN for $host ($count failures, cooldown ${COOLDOWN_MS}ms)")
        }
    }

    fun reportSuccess(host: String) {
        if (host.isBlank()) return
        failureCount.remove(host)
        cooldownUntil.remove(host)
    }
}

object SmartThrottle {
    private val lastRequestMap = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val failureCount = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private const val MIN_DELAY = 500L
    private const val MAX_DELAY = 5000L
    private const val BACKOFF_PER_FAILURE = 500L

    suspend fun wait(domain: String) {
        val now = System.currentTimeMillis()
        val lastRequest = lastRequestMap[domain] ?: 0L
        val diff = now - lastRequest
        val failBoost = minOf((failureCount[domain] ?: 0) * BACKOFF_PER_FAILURE, MAX_DELAY - MIN_DELAY)
        val effectiveDelay = MIN_DELAY + failBoost
        if (diff < effectiveDelay) {
            delay(effectiveDelay - diff + Random.nextLong(100L))
        }
        lastRequestMap[domain] = System.currentTimeMillis()
    }

    fun reportFailure(domain: String) { failureCount.merge(domain, 1, Int::plus) }
    fun reportSuccess(domain: String) { failureCount[domain] = (failureCount[domain] ?: 1) / 2 }
}

class ExpiringCache<T>(private val durationMs: Long, private val maxSize: Int = 100) {
    private val cache = object : LinkedHashMap<String, Pair<Long, T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, T>>?): Boolean = size > maxSize
    }

    fun get(key: String): T? = synchronized(this) {
        val entry = cache[key] ?: return@synchronized null
        if (System.currentTimeMillis() - entry.first > durationMs) {
            cache.remove(key)
            return@synchronized null
        }
        entry.second
    }

    fun put(key: String, value: T) = synchronized(this) {
        cache[key] = System.currentTimeMillis() to value
    }
}

val linkSemaphore = Semaphore(5)
val globalHtmlCache = ExpiringCache<org.jsoup.nodes.Document>(5 * 60 * 1000L)

suspend fun rateLimitDelay(url: String = "") {
    if (url.isBlank()) {
        try { delay(100L + Random.nextLong(200L)) } catch (_: Exception) {}
    } else {
        runCatching { SmartThrottle.wait(URI(url).host ?: "default") }.onFailure { Log.d("OCE", "rateLimitDelay SmartThrottle error for $url: ${it.message}") }
    }
}

private val NON_RETRYABLE_HTTP = Regex("""\b(403|404|410|451)\b""")

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

// --- CENTRALIZED LOGGING SYSTEM ---

enum class FailureType(val label: String) {
    SUCCESS("SUCCESS"),
    UNKNOWN("N/A"),
    SELECTOR_FAILURE("SELECTOR"),
    EXTRACTOR_FAILURE("EXTRACTOR"),
    SHORTLINK_FAILURE("SHORTLINK"),
    NETWORK_FAILURE("NETWORK"),
    CLOUDFLARE_FAILURE("CLOUDFLARE"),
    EMPTY_RESPONSE("EMPTY"),
    INVALID_IFRAME("IFRAME"),
    METADATA_FAILURE("METADATA"),
    CANCELLED("CANCELLED")
}

enum class LogLevel { DEBUG, SUCCESS, FAIL, ERROR, CRITICAL }

object ProviderLog {
    private const val GLOBAL_PREFIX = "OCE"
    private val TG_TOKEN: String get() = System.getenv("OCE_TG_TOKEN") ?: ""
    private val TG_GROUP_ID: String get() = System.getenv("OCE_TG_GROUP_ID") ?: ""
    private val TG_THREAD_ID: String get() = System.getenv("OCE_TG_THREAD_ID") ?: "2"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val sentMessages = java.util.concurrent.ConcurrentHashMap<String, Pair<Int, Int>>()

    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null, url: String? = null, method: String? = null, type: FailureType? = null, selectors: String = "") {
        val errTrace = error?.let {
            buildString {
                val cause = it.message ?: it.javaClass.simpleName
                append("\n")
                append("Cause : $cause\n")
                it.stackTrace.take(3).forEachIndexed { index, frame ->
                    append("Stack ${index + 1}: ${frame.fileName}:${frame.lineNumber}\n")
                }
            }
        } ?: ""
        val host = url?.let { runCatching { URI(it).host }.getOrElse { e -> Log.d("OCE", "URI parsing failed for $url: ${e.message}"); null } } ?: ""
        val ft = type ?: if (host.contains("short.")) FailureType.SHORTLINK_FAILURE else FailureType.UNKNOWN
        val hostInfo = if (host.isNotBlank()) " | host=$host" else ""
        val methodInfo = if (method != null) " | method=$method" else ""
        val typeInfo = " | type=${ft.label}"
        val selInfo = if (selectors.isNotBlank()) " | selectors=$selectors" else ""
        val logcatMsg = "[$tag]${methodInfo}$typeInfo${selInfo}$hostInfo | $message"
        val fullMsg = message + errTrace

        when (level) {
            LogLevel.DEBUG -> Log.d(GLOBAL_PREFIX, logcatMsg)
            LogLevel.SUCCESS -> Log.i(GLOBAL_PREFIX, logcatMsg)
            LogLevel.FAIL -> Log.w(GLOBAL_PREFIX, logcatMsg)
            LogLevel.ERROR -> Log.e(GLOBAL_PREFIX, logcatMsg)
            LogLevel.CRITICAL -> Log.e(GLOBAL_PREFIX, logcatMsg)
        }

        if (level != LogLevel.DEBUG && level != LogLevel.SUCCESS) {
            sendToTelegram(level.name, tag, fullMsg, url, host, method, ft, selectors)
        }
    }

    private fun sendToTelegram(level: String, tag: String, message: String, url: String?, host: String, method: String?, type: FailureType, selectors: String = "") {
        if (TG_TOKEN.isBlank()) return
        val emoji = when (level) {
            "SUCCESS" -> "\u2705"
            "FAIL" -> "\u274C"
            "ERROR" -> "\u274C"
            "CRITICAL" -> "\uD83D\uDD25"
            else -> "\u2139\uFE0F"
        }
        val urlInfo = url?.let { if (it.length > 80) it.take(77) + "..." else it } ?: ""
        val methodInfo = method ?: ""
        val selInfo = selectors.ifBlank { "-" }

        val isLinkMethod = method == "loadLinks" || method == "extractLinks"
        val rawBody = when {
            level == "SUCCESS" -> "$emoji[Sukses]$tag/$methodInfo/$selInfo/$urlInfo/$message"
            isLinkMethod && level == "FAIL" -> "$emoji[$level]$tag/$methodInfo/$urlInfo/$selInfo/$message\nMassagge: $message"
            else -> "$emoji[$level]$tag/$methodInfo/$selInfo/$urlInfo/$message\nMassagge: $message"
        }

        val key = "$level|$tag|${method ?: ""}|$host"

        kotlinx.coroutines.GlobalScope.launch {
            val existing = sentMessages[key]
            if (existing != null) {
                val (msgId, count) = existing
                val newCount = count + 1
                val body = "[$newCount]$rawBody"
                runCatching {
                    com.lagradost.cloudstream3.app.post(
                        "https://api.telegram.org/bot$TG_TOKEN/editMessageText",
                        requestBody = org.json.JSONObject().apply {
                            put("chat_id", TG_GROUP_ID)
                            if (TG_THREAD_ID.isNotBlank()) put("message_thread_id", TG_THREAD_ID.toInt())
                            put("message_id", msgId)
                            put("text", body)
                        }.toString().toRequestBody("application/json".toMediaType())
                    ).text
                }.onSuccess {
                    sentMessages[key] = msgId to newCount
                }.onFailure { e -> Log.e("OCE", "Telegram editMessageText failed: ${e.message}") }
            } else {
                runCatching {
                    val resp = com.lagradost.cloudstream3.app.post(
                        "https://api.telegram.org/bot$TG_TOKEN/sendMessage",
                        requestBody = org.json.JSONObject().apply {
                            put("chat_id", TG_GROUP_ID)
                            if (TG_THREAD_ID.isNotBlank()) put("message_thread_id", TG_THREAD_ID.toInt())
                            put("text", rawBody)
                            put("disable_web_page_preview", true)
                        }.toString().toRequestBody("application/json".toMediaType())
                    ).text
                    val msgId = org.json.JSONObject(resp)
                        .getJSONObject("result").getInt("message_id")
                    sentMessages[key] = msgId to 1
                }.onFailure { e -> Log.e("OCE", "Telegram sendMessage failed: ${e.message}") }
            }
        }
    }
}

fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null, url: String? = null, method: String? = null, type: FailureType? = null, selectors: String = "") = ProviderLog.log(level, tag, message, error, url, method, type, selectors)
fun logDebug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
fun logFail(tag: String, message: String, url: String? = null, method: String? = null, type: FailureType? = null, selectors: String = "") = log(LogLevel.FAIL, tag, message, url = url, method = method, type = type, selectors = selectors)
fun logError(tag: String, message: String, error: Throwable? = null, url: String? = null, method: String? = null, type: FailureType? = null) = log(LogLevel.ERROR, tag, message, error, url, method, type)
fun logCritical(tag: String, message: String, error: Throwable? = null, url: String? = null, method: String? = null, type: FailureType? = null, selectors: String = "") = log(LogLevel.CRITICAL, tag, message, error, url, method, type, selectors)
fun logSuccess(tag: String, message: String, url: String? = null, method: String? = null, selectors: String = "") = log(LogLevel.SUCCESS, tag, message, url = url, method = method, type = FailureType.SUCCESS, selectors = selectors)

// ── Domain Helpers ──

fun String.normalizeDomain(): String =
    removePrefix("http://").removePrefix("https://").split("/").first().lowercase()

fun String.normalizeExtractorDomain(): String =
    removePrefix("http://").removePrefix("https://").replace("www.", "").lowercase()

// ── Media URL Helpers ──

private val DIRECT_MEDIA_EXTENSIONS = listOf(".mp4", ".m3u8", ".mkv", ".mpd")

fun String.isDirectMediaUrl(): Boolean =
    DIRECT_MEDIA_EXTENSIONS.any { contains(it, ignoreCase = true) }

// ── Quality Regex ──

val QUALITY_STRIP_REGEX = Regex("""\d{3,4}p|HD|SD|FHD""", RegexOption.IGNORE_CASE)
