package com.baseprovider.cache

class ExpiringCache<T>(private val durationMs: Long, private val maxSize: Int = 100) {
    private val cache = object : LinkedHashMap<String, Pair<Long, T>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, T>>?): Boolean = size > maxSize
    }

    fun get(key: String): T? = synchronized(this) {
        val entry = cache[key] ?: return@synchronized null
        if (System.currentTimeMillis() - entry.first > durationMs) {
            cache.remove(key)
            return@synchronized null
        }
        entry.second
    }

    fun put(key: String, value: T) = synchronized(this) {
        cache[key] = System.currentTimeMillis() to value
    }
}


