package com.baseprovider

import com.baseprovider.network.CLOUDFLARE_HTTP
import com.baseprovider.network.HttpStatusException
import com.baseprovider.network.NON_RETRYABLE_HTTP
import com.baseprovider.network.executeWithRetry
import com.baseprovider.network.parseRetryAfter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `HttpStatusException carries body for CF detection`() {
        val e = HttpStatusException(403, null, "HTTP 403 on https://example.com", "Just a moment...")
        assertEquals("Just a moment...", e.body)
        assertTrue(CLOUDFLARE_HTTP.containsMatchIn(e.body))
    }

    // ── CLOUDFLARE_HTTP regex ──

    @Test
    fun `CLOUDFLARE_HTTP matches real Cloudflare challenge indicators`() {
        val samples = listOf(
            "Just a moment...",
            "<title>Just a moment...</title>",
            "__cf_chl_js_api_t",
            "cf-chl-bypass",
            "/cdn-cgi/challenge-platform/h/b/ordf/12345",
            "cf-ray: 8f3e2a1b2c3d4e5f-LAX",
            "Attention Required! | Cloudflare"
        )
        for (s in samples) assertTrue("should match: $s", CLOUDFLARE_HTTP.containsMatchIn(s))
    }

    @Test
    fun `CLOUDFLARE_HTTP ignores plain 403 without CF markers`() {
        val samples = listOf(
            "HTTP 403 on https://example.com/watch/ep-1",
            "HTTP 403 Forbidden",
            "403"
        )
        for (s in samples) assertFalse("should NOT match: $s", CLOUDFLARE_HTTP.containsMatchIn(s))
    }

    @Test
    fun `CLOUDFLARE_HTTP is case insensitive`() {
        assertTrue(CLOUDFLARE_HTTP.containsMatchIn("JUST A MOMENT..."))
        assertTrue(CLOUDFLARE_HTTP.containsMatchIn("CLOUDFLARE"))
    }

    @Test
    fun `NON_RETRYABLE_HTTP matches 404 410 451 but not 403`() {
        assertTrue(NON_RETRYABLE_HTTP.containsMatchIn("HTTP 404 on url"))
        assertTrue(NON_RETRYABLE_HTTP.containsMatchIn("HTTP 410 Gone"))
        assertTrue(NON_RETRYABLE_HTTP.containsMatchIn("HTTP 451"))
        assertFalse(NON_RETRYABLE_HTTP.containsMatchIn("HTTP 403 on url"))
    }

    // ── executeWithRetry branching ──

    @Test
    fun `executeWithRetry returns immediately on success`() = runBlocking {
        var calls = 0
        val result = executeWithRetry(maxRetries = 3, initialDelay = 5, maxDelay = 50) { calls++; "ok" }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `executeWithRetry propagates non-retryable 404 without retry`() = runBlocking {
        var calls = 0
        try {
            executeWithRetry<Unit>(maxRetries = 3, initialDelay = 5, maxDelay = 50) {
                calls++
                throw HttpStatusException(404, null, "HTTP 404 on url")
            }
            fail("expected 404 to propagate")
        } catch (e: HttpStatusException) {
            assertEquals(1, calls)
            assertEquals(404, e.code)
        }
    }

    @Test
    fun `executeWithRetry propagates Cloudflare challenge in message without retry`() = runBlocking {
        var calls = 0
        try {
            executeWithRetry<Unit>(maxRetries = 3, initialDelay = 5, maxDelay = 50) {
                calls++
                throw HttpStatusException(403, null, "HTTP 403 cloudflare challenge on url")
            }
            fail("expected CF exception to propagate")
        } catch (e: HttpStatusException) {
            assertEquals(1, calls)
            assertEquals(403, e.code)
        }
    }

    @Test
    fun `executeWithRetry propagates Cloudflare challenge detected in body without retry`() = runBlocking {
        var calls = 0
        try {
            executeWithRetry<Unit>(maxRetries = 3, initialDelay = 5, maxDelay = 50) {
                calls++
                throw HttpStatusException(403, null, "HTTP 403 on https://example.com", "Just a moment...")
            }
            fail("expected CF exception to propagate")
        } catch (e: HttpStatusException) {
            assertEquals(1, calls)
            assertEquals(403, e.code)
        }
    }

    @Test
    fun `executeWithRetry retries transient failure then succeeds`() = runBlocking {
        var calls = 0
        val result = executeWithRetry(maxRetries = 3, initialDelay = 5, maxDelay = 50) {
            calls++
            if (calls < 2) throw RuntimeException("transient HTTP 500")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    @Test
    fun `executeWithRetry retries 429 rate limit then succeeds`() = runBlocking {
        var calls = 0
        val result = executeWithRetry(maxRetries = 3, initialDelay = 5, maxDelay = 50) {
            calls++
            if (calls < 2) throw HttpStatusException(429, null, "HTTP 429 Too Many Requests")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    @Test
    fun `executeWithRetry throws last failure after exhausting retries`() = runBlocking {
        var calls = 0
        try {
            executeWithRetry<Unit>(maxRetries = 2, initialDelay = 5, maxDelay = 50) {
                calls++
                throw RuntimeException("persistent HTTP 500")
            }
            fail("expected exhaustion")
        } catch (e: Exception) {
            assertEquals(2, calls)
            assertTrue(e.message.orEmpty().contains("persistent HTTP 500"))
        }
    }

    @Test
    fun `executeWithRetry propagates cancellation without retry`() = runBlocking {
        var calls = 0
        try {
            executeWithRetry<Unit>(maxRetries = 3, initialDelay = 5, maxDelay = 50) {
                calls++
                throw CancellationException("user cancel")
            }
            fail("expected cancellation to propagate")
        } catch (e: CancellationException) {
            assertEquals(1, calls)
        }
    }
}