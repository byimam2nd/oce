package com.baseprovider

import org.junit.Assert.*
import org.junit.Test

class ExpiringCacheTest {

    @Test
    fun `put and get returns value`() {
        val cache = ExpiringCache<String>(5000)
        cache.put("key1", "value1")
        assertEquals("value1", cache.get("key1"))
    }

    @Test
    fun `get returns null for missing key`() {
        val cache = ExpiringCache<String>(5000)
        assertNull(cache.get("nonexistent"))
    }

    @Test
    fun `get returns null after expiry`() {
        val cache = ExpiringCache<String>(-1)
        cache.put("key", "value")
        assertNull(cache.get("key"))
    }

    @Test
    fun `evicts eldest entry when over max size`() {
        val cache = ExpiringCache<String>(5000, maxSize = 2)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.put("c", "3")
        assertNull(cache.get("a"))
        assertNotNull(cache.get("b"))
        assertNotNull(cache.get("c"))
    }

    @Test
    fun `put with same key overwrites`() {
        val cache = ExpiringCache<String>(5000)
        cache.put("key", "old")
        cache.put("key", "new")
        assertEquals("new", cache.get("key"))
    }
}
