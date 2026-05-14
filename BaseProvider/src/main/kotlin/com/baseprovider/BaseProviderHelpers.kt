package com.baseprovider

import com.lagradost.api.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
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

enum class LogLevel { DEBUG, FAIL, ERROR, CRITICAL }

object ProviderLog {
    private const val GLOBAL_PREFIX = "OCE"
    private const val TG_TOKEN = "8989495909:AAF8o8MhVa2o0T3X21N0bC3pJnMMqnvL628"
    private const val TG_USER_ID = "832658254"
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    private val sentMessages = java.util.concurrent.ConcurrentHashMap<String, Int>()

    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null, url: String? = null, method: String? = null) {
        val errTrace = error?.let {
            buildString {
                append("\n")
                append("Cause : ${it.message ?: it.javaClass.simpleName}\n")
                it.stackTrace.take(3).forEachIndexed { index, frame ->
                    append("Stack ${index + 1}: ${frame.fileName}:${frame.lineNumber}\n")
                }
            }
        } ?: ""
        val host = url?.let { runCatching { URI(it).host }.getOrNull() } ?: ""
        val hostInfo = if (host.isNotBlank()) " | host=$host" else ""
        val methodInfo = if (method != null) " | method=$method" else ""
        val logcatMsg = "[$tag]${methodInfo}$hostInfo | $message"
        val fullMsg = message + errTrace

        when (level) {
            LogLevel.DEBUG -> Log.d(GLOBAL_PREFIX, logcatMsg)
            LogLevel.FAIL -> Log.w(GLOBAL_PREFIX, logcatMsg)
            LogLevel.ERROR -> Log.e(GLOBAL_PREFIX, logcatMsg)
            LogLevel.CRITICAL -> Log.e(GLOBAL_PREFIX, logcatMsg)
        }

        if (level != LogLevel.DEBUG) {
            sendToTelegram(level.name, tag, fullMsg, url, host, method)
        }
    }

    private fun sendToTelegram(level: String, tag: String, message: String, url: String?, host: String, method: String?) {
        val now = dateFormat.format(Date())
        val emoji = when (level) {
            "FAIL" -> "\u26A0\uFE0F"
            "ERROR" -> "\u274C"
            "CRITICAL" -> "\uD83D\uDD25"
            else -> "\u2139\uFE0F"
        }

        val body = buildString {
            append("$emoji <b>[$level]</b> $tag")
            if (method != null) append(" / $method")
            append("\n\n")
            append("<pre>")
            append("Provider : $tag\n")
            if (method != null) append("Method   : $method\n")
            if (host.isNotBlank()) append("Host     : $host\n")
            if (!url.isNullOrBlank()) append("Page     : $url\n")
            append("Error    : $message\n")
            append("Time     : $now")
            append("</pre>")
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
                val resp = com.lagradost.cloudstream3.app.post(
                    "https://api.telegram.org/bot$TG_TOKEN/sendMessage",
                    data = mapOf(
                        "chat_id" to TG_USER_ID,
                        "text" to body,
                        "parse_mode" to "HTML",
                        "disable_web_page_preview" to "true"
                    )
                ).text
                val msgId = org.json.JSONObject(resp)
                    .getJSONObject("result").getInt("message_id")
                sentMessages[key] = msgId
            }
        }
    }
}

fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null, url: String? = null, method: String? = null) = ProviderLog.log(level, tag, message, error, url, method)
fun logDebug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
fun logFail(tag: String, message: String, url: String? = null, method: String? = null) = log(LogLevel.FAIL, tag, message, url = url, method = method)
fun logError(tag: String, message: String, error: Throwable? = null, url: String? = null, method: String? = null) = log(LogLevel.ERROR, tag, message, error, url, method)
fun logCritical(tag: String, message: String, error: Throwable? = null, url: String? = null, method: String? = null) = log(LogLevel.CRITICAL, tag, message, error, url, method)

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
