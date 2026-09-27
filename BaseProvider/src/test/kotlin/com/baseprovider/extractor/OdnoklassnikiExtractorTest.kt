package com.baseprovider.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OdnoklassnikiExtractorTest {

    private val extractor = Odnoklassniki()

    /** Potongan payload ok.ru nyata: JSON di-escape dengan HTML entity `&quot;`. */
    private val htmlEntityEscaped =
        """{"movie":{"id":1282642676402,"hlsManifestUrl":"https://ok6-9.vkuser.net/video.m3u8?cmd=videoPlayerCdn\\u0026expires=1790571775362\\u0026srcIp=124.40.251.88\\u0026pr=10","title":"WJDZ - 01"}}"""

    /** Varian lama: hanya backslash escape, tanpa HTML entity. */
    private val backslashEscaped =
        """{\"movie\":{\"hlsManifestUrl\":\"https://ok6-9.vkuser.net/video.m3u8?cmd=videoPlayerCdn\\u0026expires=1790571775362\"}}"""

    private fun extractHls(raw: String): String? =
        extractor.HLS_MANIFEST_RE.find(extractor.normalize(raw))
            ?.groupValues?.getOrNull(1)

    @Test
    fun `hlsManifestUrl ter-ekstrak dari payload ber-HTML-entity`() {
        val url = extractHls(htmlEntityEscaped)
        assertTrue("hlsManifestUrl harus ter-ekstrak setelah normalisasi", url != null)
        assertEquals(
            "https://ok6-9.vkuser.net/video.m3u8?cmd=videoPlayerCdn\\u0026expires=1790571775362\\u0026srcIp=124.40.251.88\\u0026pr=10",
            url
        )
    }

    @Test
    fun `hlsManifestUrl tetap ter-ekstrak dari payload backslash-escaped`() {
        assertTrue(extractHls(backslashEscaped) != null)
    }

    @Test
    fun `tanpa normalisasi payload lama gagal_match - guard regresi`() {
        // Bukti root cause: transform lama hanya mengganti \\&quot; sehingga
        // &quot;hlsManifestUrl&quot; tidak pernah match.
        val legacy = htmlEntityEscaped.replace("\\&quot;", "\"").replace("\\\\", "\\")
        assertEquals(null, extractor.HLS_MANIFEST_RE.find(legacy)?.groupValues?.getOrNull(1))
    }

    @Test
    fun `ampersand entity di-decode ke karakter aslinya`() {
        assertEquals("a&b", extractor.normalize("a&amp;b"))
    }
}
