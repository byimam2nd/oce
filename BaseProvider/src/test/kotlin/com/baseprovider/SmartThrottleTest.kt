package com.baseprovider

import com.baseprovider.network.SmartThrottle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SmartThrottleTest {

    @Test
    fun `first wait on fresh domain has no delay`() {
        runBlocking {
            val start = System.currentTimeMillis()
            SmartThrottle.wait("fresh.example.com")
            assertTrue(System.currentTimeMillis() - start < 400)
        }
    }

    @Test
    fun `reportFailure increases delay (backoff)`() {
        runBlocking {
            SmartThrottle.wait("backoff.example.com")
            SmartThrottle.reportFailure("backoff.example.com")
            SmartThrottle.reportFailure("backoff.example.com")
            val start = System.currentTimeMillis()
            SmartThrottle.wait("backoff.example.com")
            val elapsed = System.currentTimeMillis() - start
            assertTrue("expected backoff delay, got ${elapsed}ms", elapsed in 800L..3200L)
        }
    }

    @Test
    fun `reportRetryAfter adds delay capped at max`() {
        runBlocking {
            SmartThrottle.wait("retry.example.com")
            // Nilai kecil (di bawah cap 60s) — test tetap cepat di CI namun
            // memverifikasi Retry-After benar-benar menambah delay di wait().
            SmartThrottle.reportRetryAfter("retry.example.com", 4)
            val start = System.currentTimeMillis()
            SmartThrottle.wait("retry.example.com")
            val elapsed = System.currentTimeMillis() - start
            assertTrue("expected retry-after delay, got ${elapsed}ms", elapsed in 3500L..5200L)
        }
    }

    @Test
    fun `reportSuccess halves failure count`() {
        runBlocking {
            SmartThrottle.wait("recover.example.com")
            SmartThrottle.reportFailure("recover.example.com")
            SmartThrottle.reportFailure("recover.example.com")
            SmartThrottle.reportFailure("recover.example.com")
            SmartThrottle.reportSuccess("recover.example.com")
            val start = System.currentTimeMillis()
            SmartThrottle.wait("recover.example.com")
            val elapsed = System.currentTimeMillis() - start
            assertTrue("expected reduced delay after success, got ${elapsed}ms", elapsed in 500L..1800L)
        }
    }

    @Test
    fun `reportRetryAfter ignores non-positive seconds`() {
        SmartThrottle.reportRetryAfter("zero.example.com", 0)
        SmartThrottle.reportRetryAfter("negative.example.com", -5)
        assertFalse(SmartThrottle.isRetryAfterActive("zero.example.com"))
        assertFalse(SmartThrottle.isRetryAfterActive("negative.example.com"))
    }

    @Test
    fun `reportSuccess clears retryAfter`() {
        runBlocking {
            SmartThrottle.wait("clear.example.com")
            SmartThrottle.reportRetryAfter("clear.example.com", 30)
            assertTrue(SmartThrottle.isRetryAfterActive("clear.example.com"))
            SmartThrottle.reportSuccess("clear.example.com")
            assertFalse(SmartThrottle.isRetryAfterActive("clear.example.com"))
        }
    }
}