package com.baseprovider.network

import com.lagradost.api.Log
import kotlinx.coroutines.delay
import java.net.URI
import kotlin.random.Random

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

suspend fun rateLimitDelay(url: String = "") {
    if (url.isBlank()) {
        try { delay(100L + Random.nextLong(200L)) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) {}
    } else {
        runCatching {
            SmartThrottle.wait(URI(url).host ?: "default")
        }.onFailure {
            Log.d("OCE", "rateLimitDelay SmartThrottle error for $url: ${it.message}")
        }
    }
}
