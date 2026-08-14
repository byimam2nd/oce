package com.baseprovider.cache

import java.util.concurrent.ConcurrentHashMap

class ExpiringCache<T>(private val durationMs: Long,
    private val maxSize: Int = 100) {
    // L6: ConcurrentHashMap — get() murni read tanpa lock (tidak perlu
    // akses-order LRU dari LinkedHashMap yang mensyaratkan mutasi tiap read).
    // Eviction kapasitas hanya di jalur put, jadi read yang sering tidak
    // pernah terkunci. Entry evict paling lama adalah yang paling lama
    // dimasukkan (berdasarkan timestamp put).
    private val cache = ConcurrentHashMap<String, Pair<Long, T>>()

    fun get(key: String): T? {
        val entry = cache[key] ?: return null
        if (System.currentTimeMillis() - entry.first > durationMs) {
            cache.remove(key, entry)
            return null
        }
        return entry.second
    }

    fun put(key: String, value: T) {
        val now = System.currentTimeMillis()
        cache[key] = now to value
        // Baris paling mahal (scan) hanya terjadi saat sudah over kapasitas.
        if (cache.size > maxSize) evictOldest()
    }

    private fun evictOldest() {
        var oldestKey: String? = null
        var oldestTime = Long.MAX_VALUE
        for ((k, v) in cache) {
            // Tie-break deterministik berbasis key saat timestamp sama (put
            // dalam ms yang sama): tanpa tie-break, eviction menjadi acak dan
            // membuat perilaku cache tidak deterministik.
            if (v.first < oldestTime ||
                (v.first == oldestTime && (oldestKey == null || k < oldestKey))) {
                oldestTime = v.first
                oldestKey = k
            }
        }
        oldestKey?.let { cache.remove(it) }
    }
}
