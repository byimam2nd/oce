package com.baseprovider.network

import com.lagradost.api.Log

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
