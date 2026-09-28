package com.baseprovider

import com.baseprovider.log.deriveRunErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeriveRunErrorTypeTest {

    @Test
    fun `run sukses tidak punya error type`() {
        assertNull(deriveRunErrorType(listOf(null, null)))
    }

    @Test
    fun `ambil error type dari step yang gagal`() {
        assertEquals("TIMEOUT", deriveRunErrorType(listOf(null, "TIMEOUT")))
    }

    @Test
    fun `lebih dari satu tipe pilih yang pertama sesuai urutan step`() {
        // Urutan step = urutan kronologis, jadi kemunculan pertama = penyebab
        // pertama, bukan yang paling parah.
        assertEquals("NETWORK_FAILURE",
            deriveRunErrorType(listOf(null, "NETWORK_FAILURE", "TIMEOUT")))
    }

    @Test
    fun `semua step null tetap null`() {
        assertNull(deriveRunErrorType(emptyList()))
    }

    @Test
    fun `step null lalu error lalu null tetap ambil error`() {
        assertEquals("CONTENT_REMOVED",
            deriveRunErrorType(listOf(null, "CONTENT_REMOVED", null)))
    }

    @Test
    fun `string kosong dianggap tidak ada error type`() {
        // Step yang tidak punya error_type bisa masuk sebagai string kosong,
        // bukan null. Kalau tidak difilter, run sukses dapat error type "".
        assertNull(deriveRunErrorType(listOf("", "  ", null)))
    }
}
