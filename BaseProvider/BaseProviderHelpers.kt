package com.baseprovider

import com.lagradost.api.Log
import kotlinx.coroutines.delay
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

object ProviderLog {
    private const val GLOBAL_PREFIX = "OCE"

    fun d(tag: String, message: String) {
        Log.d(GLOBAL_PREFIX, "[$tag] DEBUG: $message")
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(GLOBAL_PREFIX, "[$tag] CRITICAL_ERROR: $message")
        error?.let { 
            Log.e(GLOBAL_PREFIX, "[$tag] CAUSE: ${it.message}")
            it.stackTrace.take(3).forEach { trace -> 
                Log.e(GLOBAL_PREFIX, "[$tag] AT: $trace")
            }
        }
    }
}

fun logDebug(tag: String, message: String) = ProviderLog.d(tag, message)
fun logError(tag: String, message: String, error: Throwable? = null) = ProviderLog.e(tag, message, error)

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
