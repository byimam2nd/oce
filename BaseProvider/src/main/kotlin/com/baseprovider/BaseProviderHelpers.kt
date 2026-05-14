package com.baseprovider

import com.lagradost.api.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * ENGINE HELPERS - V2.2.0 (STABILITY EDITION)
 * 
 * Berisi utilitas inti untuk networking, caching, logging, dan konfigurasi.
 * Tidak mengandung logika parsing HTML (Jsoup).
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
    private const val TG_TOKEN = "8989495909:AAF8o8MhVa2o0T3X21N0bC3pJnMMqnvL628"
    private const val TG_USER_ID = "832658254"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val sentMessages = java.util.concurrent.ConcurrentHashMap<String, Int>()

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
        val host = url?.let { runCatching { URI(it).host }.getOrNull() } ?: ""
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

        if (level != LogLevel.DEBUG) {
            sendToTelegram(level.name, tag, fullMsg, url, host, method, ft, selectors)
        }
    }

    private fun sendToTelegram(level: String, tag: String, message: String, url: String?, host: String, method: String?, type: FailureType, selectors: String = "") {
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
        val body = when {
            level == "SUCCESS" -> "$emoji[Sukses]$tag/$methodInfo/$selInfo/$urlInfo/$message"
            isLinkMethod && level == "FAIL" -> "$emoji[$level]$tag/$methodInfo/$urlInfo/$selInfo/$message\nMassagge: $message"
            else -> "$emoji[$level]$tag/$methodInfo/$selInfo/$urlInfo/$message\nMassagge: $message"
        }

        val key = "$level|$tag|$host"

        kotlinx.coroutines.GlobalScope.launch {
            val existingId = sentMessages[key]
            if (existingId != null) {
                runCatching {
                    com.lagradost.cloudstream3.app.post(
                        "https://api.telegram.org/bot$TG_TOKEN/deleteMessage",
                        data = mapOf(
                            "chat_id" to TG_USER_ID,
                            "message_id" to existingId.toString()
                        )
                    )
                }
            }
            runCatching {
                val json = org.json.JSONObject().apply {
                    put("chat_id", TG_USER_ID)
                    put("text", body)
                    put("disable_web_page_preview", true)
                }.toString()
                val resp = com.lagradost.cloudstream3.app.post(
                    "https://api.telegram.org/bot$TG_TOKEN/sendMessage",
                    requestBody = json.toRequestBody("application/json".toMediaType())
                ).text
                val msgId = org.json.JSONObject(resp)
                    .getJSONObject("result").getInt("message_id")
                sentMessages[key] = msgId
            }
        }
    }
}

fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null, url: String? = null, method: String? = null, type: FailureType? = null) = ProviderLog.log(level, tag, message, error, url, method, type)
fun logDebug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
fun logFail(tag: String, message: String, url: String? = null, method: String? = null, type: FailureType? = null, selectors: String = "") = log(LogLevel.FAIL, tag, message, url = url, method = method, type = type, selectors = selectors)
fun logError(tag: String, message: String, error: Throwable? = null, url: String? = null, method: String? = null, type: FailureType? = null) = log(LogLevel.ERROR, tag, message, error, url, method, type)
fun logCritical(tag: String, message: String, error: Throwable? = null, url: String? = null, method: String? = null, type: FailureType? = null, selectors: String = "") = log(LogLevel.CRITICAL, tag, message, error, url, method, type, selectors)
fun logSuccess(tag: String, message: String, url: String? = null, method: String? = null, selectors: String = "") = log(LogLevel.SUCCESS, tag, message, url = url, method = method, type = FailureType.SUCCESS, selectors = selectors)

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
