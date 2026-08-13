package com.baseprovider

import com.baseprovider.network.HttpStatusException
import com.baseprovider.network.parseRetryAfter
import org.junit.Assert.*
import org.junit.Test

class NetworkUtilsTest {

    @Test
    fun `parseRetryAfter parses seconds`() {
        assertEquals(120L, parseRetryAfter("120"))
    }

    @Test
    fun `parseRetryAfter parses HTTP-date`() {
        val future = System.currentTimeMillis() + 90_000L
        val date = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", java.util.Locale.US)
            .format(java.util.Date(future))
        val result = parseRetryAfter(date)
        assertNotNull(result)
        assertTrue(result!! in 60L..120L)
    }

    @Test
    fun `parseRetryAfter returns null for blank`() {
        assertNull(parseRetryAfter(null))
        assertNull(parseRetryAfter(""))
        assertNull(parseRetryAfter("not-a-date"))
    }

    @Test
    fun `HttpStatusException carries code and retryAfter`() {
        val e = HttpStatusException(429, 30L, "HTTP 429 on test")
        assertEquals(429, e.code)
        assertEquals(30L, e.retryAfterSeconds)
        assertTrue(e.message.orEmpty().contains("429"))
    }
}