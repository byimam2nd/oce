package com.baseprovider.network

import com.lagradost.api.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.net.URI
import kotlin.random.Random

object SmartThrottle {
    private val lastRequestMap = java.util.concurrent
        .ConcurrentHashMap<String, Long>()
    private val failureCount = java.util.concurrent
        .ConcurrentHashMap<String, Int>()
    private val retryAfterUntil = java.util.concurrent
        .ConcurrentHashMap<String, Long>()
    private val pacingDelay = java.util.concurrent
        .ConcurrentHashMap<String, Long>()
    // L5: lock per-domain supaya read-check-delay-write di wait() atomik.
    // Tanpa lock, request paralel ke domain sama sama-sama membaca lastRequest
    // lama dan sama-sama melewatkan delay -> burst tanpa pacing. Mutex (bukan
    // synchronized) agar delay di dalamnya tidak memblokir thread — menunggu
    // antrian coroutine juga suspend, bukan blocking.
    private val domainLocks = java.util.concurrent
        .ConcurrentHashMap<String, Mutex>()
    private const val MIN_DELAY = 50L
    private const val MAX_DELAY = 5000L
    private const val DEFAULT_DELAY = 150L
    private const val BACKOFF_PER_FAILURE = 500L
    private const val DECAY_FACTOR = 0.85
    // Cap terpisah untuk Retry-After server: nilai ini TIDAK boleh digabung
    // dengan cap pacing (MAX_DELAY). Server mengirim "wait 60s" -> kita hormati
    // hingga batas ini, bukan memangkas ke 5s.
    private const val MAX_RETRY_AFTER_MS = 60_000L

    private fun currentDelay(domain: String): Long =
        pacingDelay[domain] ?: DEFAULT_DELAY

    suspend fun wait(domain: String) {
        val lock = domainLocks.computeIfAbsent(domain) { Mutex() }
        lock.withLock {
            val now = System.currentTimeMillis()
            val lastRequest = lastRequestMap[domain] ?: 0L
            val diff = now - lastRequest
            val base = currentDelay(domain)
            val failBoost = minOf((failureCount[domain] ?: 0) * BACKOFF_PER_FAILURE, MAX_DELAY - base)
            val retryAfterBoost = retryAfterUntil[domain]
                ?.takeIf { it > now }
                ?.let { minOf(it - now, MAX_RETRY_AFTER_MS) }
                ?: 0L
            val effectiveDelay = base + maxOf(failBoost, retryAfterBoost)
            if (diff < effectiveDelay) {
                delay(effectiveDelay - diff + Random.nextLong(100L))
            }
            lastRequestMap[domain] = System.currentTimeMillis()
        }
    }

    fun reportFailure(domain: String) {
        failureCount.merge(domain, 1, Int::plus)
        pacingDelay.compute(domain) { _, prev ->
            minOf((prev ?: DEFAULT_DELAY) * 2, MAX_DELAY)
        }
    }

    fun reportSuccess(domain: String) {
        failureCount[domain] = (failureCount[domain] ?: 1) / 2
        retryAfterUntil.remove(domain)
        // Turunkan delay pacing bertahap menuju floor MIN_DELAY saat sukses.
        pacingDelay.compute(domain) { _, prev ->
            maxOf(((prev ?: DEFAULT_DELAY) * DECAY_FACTOR).toLong(), MIN_DELAY)
        }
    }

    /** Hormati Retry-After (detik) yang dikirim server saat rate-limit. */
    fun reportRetryAfter(domain: String, seconds: Long) {
        if (seconds <= 0) return
        retryAfterUntil[domain] = System.currentTimeMillis() + seconds * 1000L
    }

    /** Apakah domain masih dalam masa tunggu Retry-After. */
    fun isRetryAfterActive(domain: String): Boolean {
        val until = retryAfterUntil[domain] ?: return false
        return until > System.currentTimeMillis()
    }
}

suspend fun rateLimitDelay(url: String = "") {
    if (url.isBlank()) {
        try { delay(100L + Random.nextLong(200L)) } catch (e: kotlinx
            .coroutines.CancellationException) { throw e } catch (_: Exception) {}
    } else {
        runCatching {
            SmartThrottle.wait(URI(url).host ?: "default")
        }.onFailure {
            Log.d("OCE", "rateLimitDelay SmartThrottle error for $url: ${it.message}")
        }
    }
}
