package com.baseprovider.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * Cache hasil decrypt/API eksternal dengan TTL adaptif.
 *
 * TTL ditentukan dari isi nilai yang di-cache, bukan angka kaku per extractor:
 * - Nilai yang stabil (URL video tanpa token/timestamp) → di-cache lama
 * - Nilai yang volatile (mengandung expires/token/timestamp presigned) →
 *   di-cache sangat pendek, karena link-nya cepat mati
 *
 * Dengan begitu extractor yang API-nya stabil dapat cache panjang, sementara
 * yang API-nya anti-cache otomatis di-skip untuk TTL lama — tanpa keputusan
 * manual per extractor.
 */
class AdaptiveDecryptCache(
    private val stableTtlMs: Long = 6 * 60 * 60 * 1000L,
    private val volatileTtlMs: Long = 60 * 1000L,
    private val maxEntries: Int = 256
) {
    private data class Entry(val value: String, val timestamp: Long, val ttl: Long)

    private val cache = ConcurrentHashMap<String, Entry>()

    fun get(key: String): String? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.timestamp > entry.ttl) {
            cache.remove(key)
            return null
        }
        return entry.value
    }

    fun put(key: String, value: String) {
        if (cache.size >= maxEntries) {
            val oldestKey = cache.entries.minByOrNull { it.value.timestamp }?.key
            if (oldestKey != null) cache.remove(oldestKey)
        }
        cache[key] = Entry(value, System.currentTimeMillis(), ttlFor(value))
    }

    private fun ttlFor(value: String): Long =
        if (isVolatile(value)) volatileTtlMs else stableTtlMs

    private fun isVolatile(value: String): Boolean {
        val lower = value.lowercase()
        return VOLATILE_PATTERNS.any { lower.contains(it) }
    }

    companion object {
        private val VOLATILE_PATTERNS = listOf(
            "_=", "expires=", "token=", "signature", "x-amz-", "policy",
            "key-pair-id", "auth_key", "md5=", "&e=", "timestamp"
        )
    }
}
