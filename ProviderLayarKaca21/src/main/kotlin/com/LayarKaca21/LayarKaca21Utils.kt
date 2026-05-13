package com.LayarKaca21

import com.lagradost.api.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.GlobalScope
import java.net.URI
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

    private val recentLogsCache = ExpiringCache<Boolean>(30 * 1000L) // 30 detik cooldown untuk pesan identik

    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null, url: String? = null) {
        val errContext = error?.let { 
            "\nCause: ${it.message}\nAt: ${it.stackTrace.take(2).joinToString(" -> ")}" 
        } ?: ""
        val fullMsg = message + errContext

        // 1. Local Logcat Logging
        when (level) {
            LogLevel.DEBUG -> Log.d(GLOBAL_PREFIX, "[$tag] DEBUG: $message")
            LogLevel.FAIL -> Log.w(GLOBAL_PREFIX, "[$tag] FAIL: $message")
            LogLevel.ERROR -> Log.e(GLOBAL_PREFIX, "[$tag] ERROR: $fullMsg")
            LogLevel.CRITICAL -> Log.e(GLOBAL_PREFIX, "[$tag] CRITICAL: $fullMsg")
        }

        // 2. Remote Telegram Reporting (Only for FAIL, ERROR, CRITICAL)
        if (level != LogLevel.DEBUG) {
            val cacheKey = "$level|$tag|$message"
            if (recentLogsCache.get(cacheKey) == null) {
                sendToTelegram(level.name, tag, fullMsg, url)
                recentLogsCache.put(cacheKey, true)
            }
        }
    }

    private fun sendToTelegram(level: String, tag: String, message: String, url: String? = null) {
        val sb = StringBuilder()
        sb.append("⚠️ *[$level]*\n")
        sb.append("*Provider:* $tag\n")
        sb.append("*Message:* $message\n")
        
        if (!url.isNullOrBlank()) {
            sb.append("\n🔗 *Link:* [Open Website]($url)\n")
        }
        
        sb.append("\n*Time:* ${java.util.Date()}")
        val formattedMsg = sb.toString()
        
        kotlinx.coroutines.GlobalScope.launch {
            runCatching {
                com.lagradost.cloudstream3.app.post(
                    "https://api.telegram.org/bot$TG_TOKEN/sendMessage",
                    data = mapOf(
                        "chat_id" to TG_USER_ID, 
                        "text" to formattedMsg, 
                        "parse_mode" to "Markdown", 
                        "disable_web_page_preview" to "true"
                    )
                )
            }
        }
    }
}

// Global Bridge Functions
fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null, url: String? = null) = ProviderLog.log(level, tag, message, error, url)
fun logDebug(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
fun logFail(tag: String, message: String, url: String? = null) = log(LogLevel.FAIL, tag, message, url = url)
fun logError(tag: String, message: String, error: Throwable? = null, url: String? = null) = log(LogLevel.ERROR, tag, message, error, url)
fun logCritical(tag: String, message: String, error: Throwable? = null, url: String? = null) = log(LogLevel.CRITICAL, tag, message, error, url)

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
