package com.baseprovider

import com.baseprovider.log.SupabaseObservability.withPluginVersion
import com.baseprovider.log.isHttpOk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SupabaseWriteOutcomeTest {

    @Test
    fun `2xx dianggap sukses`() {
        assertTrue(isHttpOk(200))
        assertTrue(isHttpOk(201))
        assertTrue(isHttpOk(204))
        assertTrue(isHttpOk(299))
    }

    @Test
    fun `error PostgREST dianggap gagal walau body terbaca`() {
        // PGRST204 kolom tidak dikenal: tidak ada exception, tapi batch ditolak.
        assertFalse(isHttpOk(400))
        assertFalse(isHttpOk(401))
        assertFalse(isHttpOk(403))
        assertFalse(isHttpOk(404))
        assertFalse(isHttpOk(409))
        assertFalse(isHttpOk(413))
        assertFalse(isHttpOk(500))
        assertFalse(isHttpOk(503))
    }

    @Test
    fun `3xx tidak dianggap sukses`() {
        // Redirect lewat proxy: PostgREST tidak menulis apa pun.
        assertFalse(isHttpOk(301))
        assertFalse(isHttpOk(302))
    }

    @Test
    fun `versi kosong tidak menambahkan kolom plugin_version`() {
        // OCE_VERSION tidak di-set (build lokal). Kalau payload tetap
        // membawa plugin_version kosong, PostgREST menolak seluruh batch dan
        // fallback sempat menyalakan diri karena alasan yang salah.
        val body = withPluginVersion(
            org.json.JSONObject().put("level", "FAIL"), version = "")
        assertFalse(body.has("plugin_version"))
    }

    @Test
    fun `versi terisi menambahkan kolom plugin_version`() {
        val body = withPluginVersion(
            org.json.JSONObject().put("level", "FAIL"), version = "29843383")
        assertEquals("29843383", body.getString("plugin_version"))
    }
}
