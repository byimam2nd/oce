package com.baseprovider.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OkRuPayloadTest {

    /** Potongan payload ok.ru nyata: JSON di-escape dengan HTML entity `&quot;`. */
    private val htmlEntityEscaped =
        """{"movie":{"id":1282642676402,"hlsManifestUrl":"https://ok6-9.vkuser.net/video.m3u8?cmd=videoPlayerCdn\\u0026expires=1790571775362\\u0026srcIp=124.40.251.88\\u0026pr=10","title":"WJDZ - 01"}}"""

    /** Varian lama: hanya backslash escape, tanpa HTML entity. */
    private val backslashEscaped =
        """{\"movie\":{\"hlsManifestUrl\":\"https://ok6-9.vkuser.net/video.m3u8?cmd=videoPlayerCdn\\u0026expires=1790571775362\"}}"""

    @Test
    fun `hlsManifestUrl ter-ekstrak dari payload ber-HTML-entity`() {
        assertEquals(
            "https://ok6-9.vkuser.net/video.m3u8?cmd=videoPlayerCdn\\u0026expires=1790571775362\\u0026srcIp=124.40.251.88\\u0026pr=10",
            OkRuPayload.extractHlsManifest(htmlEntityEscaped)
        )
    }

    @Test
    fun `hlsManifestUrl tetap ter-ekstrak dari payload backslash-escaped`() {
        assertTrue(OkRuPayload.extractHlsManifest(backslashEscaped) != null)
    }

    @Test
    fun `tanpa normalisasi payload lama gagal match - guard regresi root cause`() {
        // Transform lama hanya mengganti \&quot; sehingga &quot;hlsManifestUrl&quot;
        // tidak pernah match -> extractor 0 link.
        val legacy = htmlEntityEscaped.replace("\\&quot;", "\"").replace("\\\\", "\\")
        assertNull(OkRuPayload.HLS_MANIFEST_RE.find(legacy)?.groupValues?.getOrNull(1))
    }

    @Test
    fun `ampersand entity di-decode ke karakter aslinya`() {
        assertEquals("a&b", OkRuPayload.normalize("a&amp;b"))
    }

    @Test
    fun `payload tanpa hlsManifestUrl menghasilkan null`() {
        assertNull(OkRuPayload.extractHlsManifest("""{"movie":{"id":1}}"""))
    }
}
