package com.baseprovider.network

import com.lagradost.api.Log

object HostCircuitBreaker {
    private data class HostState(
        var failures: Int,
        var cooldownUntil: Long,
        var lastFailureAt: Long
    )

    private val states = java.util.concurrent
        .ConcurrentHashMap<String, HostState>()
    private const val MAX_FAILURES = 3
    private const val BASE_COOLDOWN_MS = 60_000L
    private const val DECAY_MS = 5 * 60_000L

    fun isOpen(host: String): Boolean {
        if (host.isBlank()) return false
        val state = states[host] ?: return false
        val now = System.currentTimeMillis()
        if (state.failures > 0 && now - state.lastFailureAt > DECAY_MS) {
            states.remove(host)
            return false
        }
        return now < state.cooldownUntil
    }

    fun reportFailure(host: String) {
        if (host.isBlank()) return
        val now = System.currentTimeMillis()
        states.compute(host) { _, prev ->
            val base = prev ?: HostState(0, 0L, now)
            val failures = if (now - base.lastFailureAt > DECAY_MS) 1
            else base.failures + 1
            val cooldownUntil = if (failures >= MAX_FAILURES) now +
                (BASE_COOLDOWN_MS * (failures - MAX_FAILURES + 1)) else 0L
            HostState(failures, cooldownUntil, now)
        }
        val state = states[host] ?: return
        if (state.failures >= MAX_FAILURES) {
            Log.d("OCE", "Circuit breaker OPEN for $host (${state.failures} failures, cooldown until ${state.cooldownUntil})")
        }
    }

    fun reportSuccess(host: String) {
        if (host.isBlank()) return
        states.remove(host)
    }
}