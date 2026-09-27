package com.baseprovider.extractor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OkRuPayloadTest {

    /**
     * Potongan payload ok.ru nyata (sampled dari /video/ dan /videoembed/):
     * JSON di-escape dengan HTML entity `&quot;`, dan `&` sebagai escape JS
     * `\u0026`.
     */
    private val htmlEntityEscaped = """{"movie":{"id":1282642676402,&quot;hlsManifestUrl&quot;:&quot;https://ok6-9.vkuser.net/video.m3u8?cmd=videoPlayerCdn\u0026expires=1790571775362&quot;}}"""

    @Test
    fun `hlsManifestUrl ter-ekstrak dari payload ber-HTML-entity`() {
        assertEquals(
            "https://ok6-9.vkuser.net/video.m3u8?cmd=videoPlayerCdn\\u0026expires=1790571775362",
            OkRuPayload.extractHlsManifest(htmlEntityEscaped)
        )
    }

    @Test
    fun `tanpa normalisasi payload lama gagal match - guard regresi root cause`() {
        // Transform lama hanya mengganti \&quot; sehingga &quot;hlsManifestUrl&quot;
        // tidak pernah match -> extractor keluar tanpa callback -> 0 link.
        val legacy = htmlEntityEscaped.replace("\\&quot;", "\"").replace("\\\\", "\\")
        assertTrue("fixture harus tetap memuat &quot;", legacy.contains("&quot;"))
        assertNull(OkRuPayload.HLS_MANIFEST_RE.find(legacy)?.groupValues?.getOrNull(1))
    }

    @Test
    fun `ampersand entity di-decode ke karakter aslinya`() {
        assertEquals("a&b", OkRuPayload.normalize("a&amp;b"))
    }

    @Test
    fun `backslash ganda di-collapse jadi escape tunggal`() {
        assertEquals("\\u0026", OkRuPayload.normalize("\\\\u0026"))
    }

    @Test
    fun `payload tanpa hlsManifestUrl menghasilkan null`() {
        assertNull(OkRuPayload.extractHlsManifest("""{"movie":{"id":1}}"""))
    }

    // ── hasPlayerPayload: deteksi video yang dihapus di ok.ru ──

    @Test
    fun `halaman generic video terhapus tanpa payload terdeteksi`() {
        // Bentuk nyata respons ok.ru untuk video yang tidak tersedia:
        // <title>Смотрите видео в ОК. Плеер Видео</title> tanpa data player.
        val deadPage = """
            <!DOCTYPE html><html><head><title>Смотрите видео в ОК. Плеер Видео</title>
            <meta property="og:image" content="https://st-ok.cdn-vk.ru/res/i/ok_logo-r.png">
            </head><body><div id="player"></div></body></html>
        """.trimIndent()
        assertFalse(OkRuPayload.hasPlayerPayload(deadPage))
    }

    @Test
    fun `halaman dengan hlsManifestUrl terdeteksi punya payload`() {
        assertTrue(
            OkRuPayload.hasPlayerPayload(
                """{"movie":{"hlsManifestUrl":"https://ok6-7.vkuser.net/video.m3u8?cmd=x"}}"""
            )
        )
    }

    @Test
    fun `halaman dengan daftar videos terdeteksi punya payload`() {
        assertTrue(
            OkRuPayload.hasPlayerPayload("""{"videos":[{"name":"480","url":"//x/y.mp4"}]}""")
        )
    }

    @Test
    fun `payload html-entity escaped tetap terdeteksi`() {
        assertTrue(
            OkRuPayload.hasPlayerPayload(
                "&quot;hlsManifestUrl&quot;:&quot;https://ok6-7.vkuser.net/v.m3u8&quot;"
            )
        )
    }
}
