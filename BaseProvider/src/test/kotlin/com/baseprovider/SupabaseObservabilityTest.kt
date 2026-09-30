package com.baseprovider

import com.baseprovider.log.SupabaseObservability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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

    @Test
    fun `fresh step may be retried`() {
        assertTrue(SupabaseObservability.shouldRetryStep(0))
    }

    @Test
    fun `step is retried until attempt bound then dropped`() {
        // Run row yang belum dibuat (atau POST gagal sesaat) boleh dicoba
        // lagi, tapi tidak tanpa batas — kalau tidak, antrian tumbuh forever
        // untuk run yang memang tidak pernah dibuat.
        for (attempts in 0 until SupabaseObservability.MAX_STEP_ATTEMPTS) {
            assertTrue("attempts=$attempts harus masih dicoba",
                SupabaseObservability.shouldRetryStep(attempts))
        }
        assertFalse(SupabaseObservability
            .shouldRetryStep(SupabaseObservability.MAX_STEP_ATTEMPTS))
        assertFalse(SupabaseObservability
            .shouldRetryStep(SupabaseObservability.MAX_STEP_ATTEMPTS + 1))
    }

    @Test
    fun `requeue increments attempts and keeps run and body`() {
        val body = org.json.JSONObject().put("kind", "EXTRACT")
        val first = SupabaseObservability.StepEntry("run-1", body)
        val second = first.copy(attempts = first.attempts + 1)
        assertEquals(0, first.attempts)
        assertEquals(1, second.attempts)
        assertEquals("run-1", second.runId)
        assertSame(body, second.body)
    }

    @Test
    fun `batch yang gagal ditulis dikembalikan ke antrian dengan attempts naik`() {
        // Kegagalan POST dulu membuang SELURUH batch apa pun isinya — 25 baris
        // hilang sekaligus. Entry yang masih punya budget harus balik antrian.
        val body = org.json.JSONObject().put("kind", "EXTRACT")
        val fresh = SupabaseObservability.StepEntry("run-1", body)
        val exhausted = fresh.copy(
            attempts = SupabaseObservability.MAX_STEP_ATTEMPTS)
        val retry = SupabaseObservability
            .retryAfterWriteFailure(listOf(fresh, exhausted))
        assertEquals(1, retry.size)
        assertEquals("run-1", retry[0].runId)
        assertEquals(1, retry[0].attempts)
        assertSame(body, retry[0].body)
    }

    @Test
    fun `seluruh isi batch yang gagal ditulis tidak hilang`() {
        val body = org.json.JSONObject().put("kind", "EXTRACT")
        val batch = (0 until 25).map {
            SupabaseObservability.StepEntry("run-1", body)
        }
        assertEquals(25,
            SupabaseObservability.retryAfterWriteFailure(batch).size)
    }

    @Test
    fun `batch yang sudah habis budget dibuang`() {
        val body = org.json.JSONObject().put("kind", "EXTRACT")
        val batch = (0 until 5).map {
            SupabaseObservability.StepEntry("run-1", body).copy(
                attempts = SupabaseObservability.MAX_STEP_ATTEMPTS)
        }
        assertTrue(SupabaseObservability.retryAfterWriteFailure(batch).isEmpty())
    }
}