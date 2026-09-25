package com.baseprovider

import com.baseprovider.log.SupabaseObservability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupabaseObservabilityTest {

    @Test
    fun `all extract steps success makes run success without summary`() {
        val r = SupabaseObservability.deriveRunEndStatus(
            collectCount = 3, extractSuccess = 3,
            extractFailed = 0, extractTimeout = 0, callerStatus = "success")
        assertEquals("success", r.status)
        assertNull(r.summary)
    }

    @Test
    fun `all extractors failed makes run failed with summary`() {
        val r = SupabaseObservability.deriveRunEndStatus(
            collectCount = 4, extractSuccess = 0,
            extractFailed = 3, extractTimeout = 1, callerStatus = "failed")
        assertEquals("failed", r.status)
        assertEquals(
            "COLLECT=4 EXTRACT=4 (ok=0 fail=3 timeout=1)", r.summary)
    }

    @Test
    fun `partial success makes run partial with summary`() {
        val r = SupabaseObservability.deriveRunEndStatus(
            collectCount = 5, extractSuccess = 2,
            extractFailed = 3, extractTimeout = 0, callerStatus = "success")
        assertEquals("partial", r.status)
        assertEquals(
            "COLLECT=5 EXTRACT=5 (ok=2 fail=3 timeout=0)", r.summary)
    }

    @Test
    fun `timeout only makes run failed with summary`() {
        val r = SupabaseObservability.deriveRunEndStatus(
            collectCount = 2, extractSuccess = 0,
            extractFailed = 0, extractTimeout = 2, callerStatus = "failed")
        assertEquals("failed", r.status)
        assertEquals(
            "COLLECT=2 EXTRACT=2 (ok=0 fail=0 timeout=2)", r.summary)
    }

    @Test
    fun `no steps keeps caller status without summary`() {
        val r = SupabaseObservability.deriveRunEndStatus(
            collectCount = 0, extractSuccess = 0,
            extractFailed = 0, extractTimeout = 0, callerStatus = "failed")
        assertEquals("failed", r.status)
        assertNull(r.summary)
    }

    @Test
    fun `partial with caller failed is still partial`() {
        val r = SupabaseObservability.deriveRunEndStatus(
            collectCount = 3, extractSuccess = 1,
            extractFailed = 2, extractTimeout = 0, callerStatus = "failed")
        assertEquals("partial", r.status)
        assertEquals(
            "COLLECT=3 EXTRACT=3 (ok=1 fail=2 timeout=0)", r.summary)
    }
}